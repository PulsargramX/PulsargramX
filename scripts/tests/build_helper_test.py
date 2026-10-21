#!/usr/bin/env python3
"""Exercise cache isolation and quiet Gradle failure reporting."""

import importlib.util
import hashlib
import io
import os
from pathlib import Path
import re
import subprocess
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("build_apk", SCRIPTS / "build-apk.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)

OVERLAY_SPEC = importlib.util.spec_from_file_location(
  "tdlib_overlay", SCRIPTS / "build-tdlib-overlay.py")
OVERLAY = importlib.util.module_from_spec(OVERLAY_SPEC)
OVERLAY_SPEC.loader.exec_module(OVERLAY)


class UpstreamBuildCompatibilityTest(unittest.TestCase):
  def test_gradle_daemon_and_ci_support_the_build_logic_jvm(self):
    root = SCRIPTS.parent
    criteria = BUILD.RELEASE.read_properties(root / "gradle/gradle-daemon-jvm.properties")
    daemon_version = int(criteria["toolchainVersion"])
    build_logic = (root / "buildSrc/build.gradle.kts").read_text()
    required_versions = re.findall(r"JavaLanguageVersion\.of\((\d+)\)|JVM_(\d+)",
                                   build_logic)
    self.assertTrue(required_versions)
    self.assertGreaterEqual(daemon_version,
                            max(int(java or kotlin) for java, kotlin in required_versions))
    workflow = (root / ".github/workflows/build-and-publish.yml").read_text()
    installed_version = re.search(r"java-version:\s*['\"](\d+)['\"]", workflow)
    self.assertIsNotNone(installed_version)
    self.assertEqual(int(installed_version.group(1)), daemon_version)

  def test_variant_helpers_reference_declared_abis(self):
    root = SCRIPTS.parent
    config = (root / "buildSrc/src/main/kotlin/Config.kt").read_text()
    abi = config.split("enum class Abi(", 1)[1].split("\ndata class SdkVariant", 1)[0]
    declared = set(re.findall(r"\bval ([A-Z][A-Z_0-9]*)", abi))
    declared.update(re.findall(r"^  ([A-Z][A-Z_0-9]*)\(\d+\)", abi, re.M))
    references = set(re.findall(r"\bAbi\.([A-Z][A-Z_0-9]*)",
                               config + (root / "app/build.gradle.kts").read_text()))
    self.assertEqual(references - declared, set())

  def test_context_callers_do_not_use_the_removed_upstream_methods(self):
    for path in (SCRIPTS.parent / "app/src").rglob("*"):
      if path.suffix not in (".java", ".kt"):
        continue
      with self.subTest(source=str(path.relative_to(SCRIPTS.parent))):
        source = re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
        self.assertNotIn("UI.getAppContext(", source)
        self.assertNotIn("UI.initApp(", source)
        self.assertNotIn("TdlibNotificationUtils.getDeviceTokenRetriever(", source)

  def test_call_library_links_the_renderer_implementation(self):
    cmake = (SCRIPTS.parent / "app/jni/tgvoip/BuildTgCalls.cmake").read_text()
    self.assertIn("/group/StreamingAudioRenderer.cpp", cmake)
    self.assertNotIn("/group/StreamingAudioRendererTest.cpp", cmake)

  def test_java25_selection_skips_an_older_java_home(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      older, current = root / "jdk21", root / "jdk25"
      for jdk in (older, current):
        (jdk / "bin").mkdir(parents=True)
        (jdk / "bin/java").touch()
      def version(executable, *args):
        return b'openjdk version "21.0.1"' if str(older) in executable else b'openjdk version "25.0.1"'
      with patch.dict(os.environ, JAVA_HOME=str(older)), \
           patch.object(BUILD.shutil, "which", return_value=str(current / "bin/java")), \
           patch.object(BUILD.Path, "glob", return_value=[]), \
           patch.object(BUILD, "run", side_effect=version):
        self.assertEqual(BUILD.java25(), current)

  def test_native_platforms_use_matching_crypto_and_separate_outputs(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      build, ndk, abi = root / "output", "30.0.16248370", "arm64-v8a"
      commands = []
      def command(arguments, *args):
        commands.append([str(arg) for arg in arguments])
        if "--build" in arguments:
          state = Path(arguments[arguments.index("--build") + 1])
          state.mkdir(parents=True, exist_ok=True)
          (state / "libtdjni.so").write_bytes(str(state).encode())
      for api in (23, 24):
        platform = f"android-{api}"
        crypto = root / "tdlib/openssl" / ndk / platform / abi
        (crypto / "lib").mkdir(parents=True)
        for library in ("libcryptox.so", "libsslx.so"):
          (crypto / "lib" / library).write_bytes(b"\x7fELF")
        arguments = SimpleNamespace(root=root, sdk=root / "sdk", ndk=ndk,
                                    abi=abi, api=api, jobs=2)
        with patch.object(OVERLAY, "command", side_effect=command):
          OVERLAY.native(arguments, build, root / "source", "revision", {},
                         "cmake", "ninja", None)
        self.assertIn(f"-DANDROID_PLATFORM={platform}", commands[-2])
        self.assertIn("-DOPENSSL_ROOT_DIR=" + str(crypto), commands[-2])
        self.assertIn("-DANDROID_STL=c++_shared", commands[-2])
        for language in ("C", "CXX"):
          flags = next(arg for arg in commands[-2] if arg.startswith(f"-DCMAKE_{language}_FLAGS="))
          self.assertIn(f"-ffile-prefix-map={root / 'source'}/=", flags)
          self.assertIn(f"-ffile-prefix-map={root / 'sdk/ndk' / ndk}/=ndk/", flags)
      first = build / "native" / ndk / "android-23" / abi / "libtdjni.so"
      second = build / "native" / ndk / "android-24" / abi / "libtdjni.so"
      self.assertTrue(first.is_file())
      self.assertTrue(second.is_file())
      self.assertNotEqual(first.read_bytes(), second.read_bytes())


class EditionReleaseBuildTest(unittest.TestCase):
  def test_both_editions_use_sdk_variants_and_explicit_arm64_selection(self):
    for edition in ("personal", "public"):
      with self.subTest(edition=edition), tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        project, cache, sdk = root / "project", root / "cache", root / "sdk"
        project.mkdir()
        cache.mkdir()
        for component in ("platforms/android-37", "ndk/30", "cmake/4", "build-tools/37"):
          (sdk / component).mkdir(parents=True)
        signing = root / "signing.properties"
        signing.write_text("keystore.file=" + str(root / "release.jks") + "\n")
        (project / "local.properties").write_text(
          f"sdk.dir={sdk}\nkeystore.file={signing}\napp.public.keystore_file={signing}\n"
          "app.id=example.personal\n")
        (project / "version.properties").write_text(
          "version.sdk_compile=37\nversion.ndk_primary=30\n"
          "version.cmake=4\nversion.build_tools=37\n")
        apk = project / "app/build/outputs/apk" / (edition + "Latest") / "release/client.apk"
        apk.parent.mkdir(parents=True)
        apk.touch()
        with patch.dict(os.environ), patch.object(BUILD, "java25", return_value=root / "jdk"), \
             patch.object(BUILD, "missing_build_tools", return_value=[]), \
             patch.object(BUILD.RELEASE, "local_signing_config", return_value=(sdk, "certificate")), \
             patch.object(BUILD.shutil, "which", return_value=None), \
             patch.object(BUILD, "invalid_binaries", return_value=[]), \
             patch.object(BUILD, "execute", return_value=0) as execute, \
             patch.object(BUILD.subprocess, "run") as verify, patch("sys.stdout", io.StringIO()):
          BUILD.worker(project, cache, edition)
        self.assertEqual(execute.call_count, 2)
        tasks = [call.args[0] for call in execute.call_args_list]
        self.assertEqual([args[1] for args in tasks],
                         [":app:externalNativeBuild" + edition.capitalize() + "LatestRelease",
                          ":app:assemble" + edition.capitalize() + "LatestRelease"])
        for args in tasks:
          self.assertIn("-Pclient.edition=" + edition, args)
          self.assertIn("-Pabi=arm64", args)
        verification = verify.call_args.args[0]
        self.assertEqual(verification[verification.index("--variant") + 1],
                         edition + "LatestRelease")
        self.assertIn(str(apk), verification)

  def test_default_release_keeps_android_7_compatibility_and_arm64_support(self):
    config = (SCRIPTS.parent / "buildSrc/src/main/kotlin/Config.kt").read_text()
    latest = re.search(r'flavor = "latest",\s*minSdk = (\d+)', config)
    self.assertIsNotNone(latest)
    self.assertEqual(int(latest.group(1)), 24)
    filters = re.search(r"val FILTERS = listOf\((.*?)\)", config, re.S)
    self.assertIsNotNone(filters)
    self.assertEqual(re.findall(r'"([^"]+)"', filters.group(1)), ["arm64-v8a"])


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

  def lfs_cache(self):
    self.git(self.source, "config", "filter.lfs.clean", "cat")
    self.git(self.source, "config", "filter.lfs.required", "false")
    (self.source / ".gitattributes").write_text("*.so filter=lfs diff=lfs merge=lfs -text\n")
    binary = b"\x7fELFcached library"
    def pointer(data):
      return ("version https://git-lfs.github.com/spec/v1\n"
              f"oid sha256:{hashlib.sha256(data).hexdigest()}\nsize {len(data)}\n").encode()
    for name in ("a.so", "b.so"):
      (self.source / name).write_bytes(pointer(binary))
    self.git(self.source, "add", ".gitattributes", "a.so", "b.so")
    self.git(self.source, "commit", "-qm", "Add LFS inputs")
    first = self.git(self.source, "rev-parse", "HEAD").strip()
    updated = pointer(b"\x7fELFupdated library")
    (self.source / "a.so").write_bytes(updated)
    self.git(self.source, "add", "a.so")
    self.git(self.source, "commit", "-qm", "Update LFS input")
    second = self.git(self.source, "rev-parse", "HEAD").strip()
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, first, self.origin)
    for name in ("a.so", "b.so"):
      (self.target / name).write_bytes(binary)
    return first, second, binary, updated

  def test_revision_change_accepts_verified_hydrated_lfs_inputs(self):
    first, second, binary, updated = self.lfs_cache()
    native = self.target / "build/native.a"
    native.parent.mkdir()
    native.write_bytes(b"keep native cache")
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, second, self.origin)
    self.assertEqual(self.git(self.target, "rev-parse", "HEAD").strip(), second)
    self.assertEqual((self.target / "a.so").read_bytes(), updated)
    self.assertEqual(native.read_bytes(), b"keep native cache")
    self.assertEqual((self.source / "app.txt").read_text(), "user's uncommitted edit\n")

  def test_same_revision_keeps_verified_lfs_inputs_hydrated(self):
    first, second, binary, updated = self.lfs_cache()
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, first, self.origin)
    for name in ("a.so", "b.so"):
      self.assertEqual((self.target / name).read_bytes(), binary)

  def test_modified_lfs_input_is_rejected_before_other_inputs_change(self):
    first, second, binary, updated = self.lfs_cache()
    modified = b"\x7fELFedited library"
    self.assertEqual(len(binary), len(modified))
    (self.target / "b.so").write_bytes(modified)
    with patch.object(BUILD, "SOURCE", self.source), \
         self.assertRaisesRegex(BUILD.BuildError, "modified LFS input"):
      BUILD.checkout(self.target, self.source, second, self.origin)
    self.assertEqual(self.git(self.target, "rev-parse", "HEAD").strip(), first)
    self.assertEqual((self.target / "a.so").read_bytes(), binary)
    self.assertEqual((self.target / "b.so").read_bytes(), modified)

  def test_staged_lfs_edits_are_preserved(self):
    first, second, binary, updated = self.lfs_cache()
    (self.target / "a.so").write_bytes(updated)
    self.git(self.target, "-c", "filter.lfs.process=", "-c", "filter.lfs.clean=cat",
             "-c", "filter.lfs.required=false", "add", "a.so")
    with patch.object(BUILD, "SOURCE", self.source), \
         self.assertRaisesRegex(BUILD.BuildError, "staged LFS edits"):
      BUILD.checkout(self.target, self.source, second, self.origin)
    self.assertEqual(self.git(self.target, "show", ":a.so").encode(), updated)
    self.assertEqual((self.target / "b.so").read_bytes(), binary)

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
