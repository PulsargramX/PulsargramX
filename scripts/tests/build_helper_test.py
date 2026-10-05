#!/usr/bin/env python3
"""Exercise cache isolation and quiet Gradle failure reporting."""

import importlib.util
import hashlib
import io
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("build_apk", SCRIPTS / "build-apk.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class QuietGradleTest(unittest.TestCase):
  def test_compiler_diagnostics_and_gradle_cause_survive_quiet_capture(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      wrapper = root / "gradlew"
      wrapper.write_text(
        '#!/bin/sh\nif [ "$1" = --stop ]; then touch stopped; exit 0; fi\n'
        'echo "Example.java:3: error: cannot find symbol"\n'
        'echo "> Task :app:compileJava FAILED"\n'
        'echo "FAILURE: Build failed with an exception."\n'
        'echo "* What went wrong:"\n'
        'echo "Execution failed for task :app:compileJava."\n'
        'echo "* Try:"\nexit 1\n'
      )
      wrapper.chmod(0o755)
      log = root / "compiler.log"
      environment = dict(os.environ, TGX_GRADLE_PROJECT_DIR=str(root),
                         TGX_GRADLE_LOG_FILE=str(log))
      result = subprocess.run([str(SCRIPTS / "gradle.sh"), "compileJava"],
                              env=environment, capture_output=True, text=True)
      self.assertEqual(result.returncode, 1)
      self.assertEqual(result.stdout, "")
      self.assertIn("Example.java:3: error: cannot find symbol", result.stderr)
      self.assertIn("Execution failed for task", result.stderr)
      self.assertTrue(log.is_file())
      self.assertTrue((root / "stopped").is_file())


class ProcessCleanupTest(unittest.TestCase):
  @unittest.skipUnless(shutil.which("java"), "Java is unavailable")
  def test_background_java_is_stopped_after_success_and_failure(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "Sleeping.java").write_text(
        "class Sleeping { public static void main(String[] args) throws Exception {"
        " Thread.sleep(60000); } }\n"
      )
      launcher = root / "launcher.sh"
      launcher.write_text(
        '#!/bin/sh\njava Sleeping.java >java.log 2>&1 &\n'
        'echo $! >java.pid\nsleep 1\nexit "$1"\n'
      )
      launcher.chmod(0o755)
      for status in (0, 1):
        self.assertEqual(BUILD.execute([str(launcher), str(status)], cwd=root), status)
        pid = int((root / "java.pid").read_text())
        state = Path(f"/proc/{pid}/stat")
        self.assertTrue(not state.exists() or state.read_text().split()[2] == "Z",
                        f"Java process {pid} survived build cleanup")

  def test_interrupted_launcher_is_terminated(self):
    process = subprocess.Popen(["sleep", "60"], start_new_session=True)
    original_wait = process.wait
    try:
      with patch.object(BUILD.subprocess, "Popen", return_value=process), \
           patch.object(process, "wait", side_effect=[KeyboardInterrupt, None]):
        with self.assertRaises(KeyboardInterrupt):
          BUILD.execute(["unused"])
      original_wait(timeout=2)
      self.assertIsNotNone(process.returncode)
    finally:
      if process.poll() is None:
        process.kill()
      original_wait()


class BuildCacheTest(unittest.TestCase):
  def setUp(self):
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    self.root = Path(self.directory.name)
    self.source = self.root / "source"
    self.source.mkdir()
    self.git(self.source, "init", "-q")
    self.git(self.source, "config", "user.name", "Build check")
    self.git(self.source, "config", "user.email", "build@example.invalid")
    (self.source / "app.txt").write_text("first\n")
    self.git(self.source, "add", "app.txt")
    self.git(self.source, "commit", "-qm", "First")
    self.first = self.git(self.source, "rev-parse", "HEAD").strip()
    (self.source / "app.txt").write_text("second\n")
    self.git(self.source, "commit", "-qam", "Second")
    self.second = self.git(self.source, "rev-parse", "HEAD").strip()
    (self.source / "app.txt").write_text("user's uncommitted edit\n")
    self.target = self.root / "cache"
    self.origin = "https://github.com/example/build-example.git"

  @staticmethod
  def git(root, *arguments):
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True,
                                   stderr=subprocess.PIPE)

  def test_revision_changes_preserve_native_cache_and_source_edits(self):
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, self.first, self.origin)
      self.assertEqual((self.target / "app.txt").read_text(), "first\n")
      native = self.target / "app/build/native.a"
      native.parent.mkdir(parents=True)
      native.write_bytes(b"cached native library")
      BUILD.checkout(self.target, self.source, self.second, self.origin)
      self.assertEqual((self.target / "app.txt").read_text(), "second\n")
      self.assertEqual(native.read_bytes(), b"cached native library")
      self.assertEqual((self.source / "app.txt").read_text(), "user's uncommitted edit\n")
      self.assertEqual(self.git(self.target, "remote", "get-url", "origin").strip(), self.origin)

  def test_manual_tracked_cache_edits_are_rejected(self):
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, self.first, self.origin)
      (self.target / "app.txt").write_text("manual cache edit\n")
      with self.assertRaisesRegex(BUILD.BuildError, "tracked edits"):
        BUILD.checkout(self.target, self.source, self.second, self.origin)
      self.assertEqual((self.target / "app.txt").read_text(), "manual cache edit\n")

  def test_working_patch_can_be_reverted_without_discarding_other_files(self):
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, self.second, self.origin)
      patch_path = self.root / "working.patch"
      BUILD.apply_working_patch(self.target, patch_path, BUILD.uncommitted(self.source))
      self.assertTrue(patch_path.read_bytes())
      unrelated = self.target / "notes.txt"
      unrelated.write_text("keep this\n")
      BUILD.git(self.target, "apply", "--reverse", "--index", str(patch_path))
      self.assertEqual(BUILD.uncommitted(self.target), b"")
      self.assertEqual(unrelated.read_text(), "keep this\n")

  def test_failed_patch_does_not_leave_recovery_marker(self):
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, self.first, self.origin)
      patch_path = self.root / "working.patch"
      with self.assertRaises(BUILD.BuildError):
        BUILD.apply_working_patch(self.target, patch_path, BUILD.uncommitted(self.source))
      self.assertFalse(patch_path.exists())
      self.assertFalse(patch_path.with_suffix(".candidate").exists())
      self.assertEqual(BUILD.uncommitted(self.target), b"")


class FailureReportingTest(unittest.TestCase):
  def test_current_error_survives_missing_and_stale_log_without_credentials(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "local.properties").write_text("api.id=private-value\n")
      log = root / "build.log"
      for exists in (False, True):
        if exists:
          log.write_text("old log line\n" * 100 + "private-value\n")
        output = io.StringIO()
        with patch.object(BUILD, "SOURCE", root), patch("sys.stderr", output):
          BUILD.report_failure(BUILD.BuildError("current cause private-value"), log)
        self.assertIn("current cause <redacted>", output.getvalue())
        self.assertNotIn("private-value", output.getvalue())
        self.assertTrue(output.getvalue().startswith("error:"))

  def test_lfs_hash_validation_detects_modified_binary(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      binary = root / "tdlib/library.so"
      binary.parent.mkdir()
      binary.write_bytes(b"committed binary")
      pointer = b"oid sha256:" + hashlib.sha256(binary.read_bytes()).hexdigest().encode()
      with patch.object(BUILD, "git", return_value=pointer):
        self.assertEqual(BUILD.invalid_binaries(root, ["library.so"]), [])
        binary.write_bytes(b"modified binary")
        self.assertEqual(BUILD.invalid_binaries(root, ["library.so"]), ["library.so"])


if __name__ == "__main__":
  unittest.main()
