#!/usr/bin/env python3
"""Exercise cache isolation and quiet Gradle failure reporting."""

import importlib.util
import hashlib
import io
import os
from pathlib import Path
import re
import signal
import subprocess
import shutil
import sys
import tempfile
import time
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
  def test_child_jdk_tools_inherit_clean_container_logging_and_caller_options(self):
    for inherited in (None, "-Dbuild.test=true -Xlog:os+container=trace"):
      with self.subTest(inherited=inherited), tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        wrapper = root / "gradlew"
        wrapper.write_text(
          '#!/usr/bin/env python3\nimport os, pathlib, sys\n'
          'stage = "cleanup" if "--stop" in sys.argv else "build"\n'
          'pathlib.Path(stage).write_text(os.environ["JAVA_TOOL_OPTIONS"])\n'
        )
        wrapper.chmod(0o755)
        environment = dict(os.environ, TGX_GRADLE_PROJECT_DIR=str(root))
        environment.pop("JAVA_TOOL_OPTIONS", None)
        if inherited is not None:
          environment["JAVA_TOOL_OPTIONS"] = inherited
        result = subprocess.run([str(SCRIPTS / "gradle.sh"), "compileJava"],
                                env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = ((inherited + " ") if inherited else "") + "-Xlog:os+container=off"
        self.assertEqual((root / "build").read_text(), expected)
        self.assertEqual((root / "cleanup").read_text(), expected)
        self.assertNotIn("JAVA_TOOL_OPTIONS", result.stdout + result.stderr)

  @unittest.skipUnless(shutil.which("jlink"), "Requires jlink")
  def test_jlink_version_stdout_remains_parseable(self):
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      wrapper = root / "gradlew"
      wrapper.write_text(
        '#!/bin/sh\n[ "$1" = --stop ] && exit 0\n'
        'jlink --version > jdk-version\n'
      )
      wrapper.chmod(0o755)
      environment = dict(os.environ, TGX_GRADLE_PROJECT_DIR=str(root),
                         JAVA_TOOL_OPTIONS="-Xlog:os+container=trace")
      result = subprocess.run([str(SCRIPTS / "gradle.sh"), "compileJava"],
                              env=environment, capture_output=True, text=True)
      self.assertEqual(result.returncode, 0, result.stderr)
      self.assertRegex((root / "jdk-version").read_text().strip(), r"^\d+[.\w+-]*$")

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


class BuildMemoryTest(unittest.TestCase):
  def test_memory_budget_reserves_ram_and_caps_the_whole_build(self):
    for total, available, expected in ((32, 24, 12), (16, 14, 8), (32, 16, 8)):
      memory = f"MemTotal: {total * 1024 ** 2} kB\nMemAvailable: {available * 1024 ** 2} kB\n"
      with self.subTest(total=total, available=available), \
           patch.object(BUILD.Path, "read_text", return_value=memory):
        self.assertEqual(BUILD.memory_limit(), expected * BUILD.GIB)

  def test_busy_and_small_hosts_are_rejected_before_compilation(self):
    for total, available in ((32, 13), (8, 8)):
      memory = f"MemTotal: {total * 1024 ** 2} kB\nMemAvailable: {available * 1024 ** 2} kB\n"
      with self.subTest(total=total, available=available), \
           patch.object(BUILD.Path, "read_text", return_value=memory), \
           self.assertRaisesRegex(BUILD.BuildError, "Not enough available RAM"):
        BUILD.memory_limit()

  def test_missing_memory_information_does_not_allow_an_unbounded_build(self):
    with patch.object(BUILD.Path, "read_text", return_value="MemTotal: 123 kB\n"), \
         self.assertRaisesRegex(BUILD.BuildError, "Cannot determine available RAM"):
      BUILD.memory_limit()

  def test_worker_rejects_missing_limits_and_unenforced_kernel_settings(self):
    with self.assertRaisesRegex(BUILD.BuildError, "memory-limited systemd scope"):
      BUILD.verify_memory_scope(None, None)
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      unit, maximum = "test-apk.scope", 64 * 1024 ** 2
      group = root / "app.slice" / unit
      group.mkdir(parents=True)
      settings = {"cgroup.procs": str(os.getpid()), "memory.max": maximum,
                  "memory.high": 53 * 1024 ** 2, "memory.swap.max": 0, "memory.oom.group": 1}
      for name, value in settings.items():
        (group / name).write_text(str(value))
      with patch.object(BUILD, "CGROUP_ROOTS", (root / "private-namespace", root)), \
           patch.object(BUILD, "run", return_value=f"/app.slice/{unit}\n".encode()):
        BUILD.verify_memory_scope(unit, maximum)
        for name in settings:
          with self.subTest(setting=name):
            (group / name).write_text("max" if name.startswith("memory.") else "0")
            with self.assertRaisesRegex(BUILD.BuildError, "refusing to start Gradle"):
              BUILD.verify_memory_scope(unit, maximum)
            (group / name).write_text(str(settings[name]))


class MemoryScopeIntegrationTest(unittest.TestCase):
  @classmethod
  def setUpClass(cls):
    if not all(shutil.which(tool) for tool in ("systemd-run", "systemctl")):
      raise unittest.SkipTest("systemd is unavailable")
    session = subprocess.run(["systemctl", "--user", "show", "-p", "Version", "--value"],
                             capture_output=True, timeout=5)
    if session.returncode:
      raise unittest.SkipTest("No systemd user session")

  def setUp(self):
    self.unit = f"client-apk-test-{os.getpid()}-{time.monotonic_ns()}.scope"
    self.maximum = 64 * 1024 ** 2
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    self.addCleanup(BUILD.stop_memory_scope, self.unit)
    self.root = Path(self.directory.name)

  def script(self, body):
    helper = str(SCRIPTS / "build-apk.py")
    return ("import importlib.util, os, subprocess, sys, time\nfrom pathlib import Path\n"
            f"spec = importlib.util.spec_from_file_location('build_apk', {helper!r})\n"
            "build = importlib.util.module_from_spec(spec)\nspec.loader.exec_module(build)\n"
            f"build.verify_memory_scope({self.unit!r}, {self.maximum})\n" + body)

  def test_limit_kills_only_the_dummy_build(self):
    # Verify the actual cgroup first, then exceed a tiny limit, never host RAM.
    verified = self.root / "verified"
    script = self.script(
      f"Path({str(verified)!r}).touch()\n"
      # Bypass soft throttling so this test reaches the hard cap promptly.
      f"subprocess.run(['systemctl', '--user', 'set-property', '--runtime', {self.unit!r}, "
      "'MemoryHigh=infinity'], check=True)\nbytearray(128 * 1024 ** 2)\n")
    result = subprocess.run(BUILD.memory_scope_command([sys.executable, "-c", script],
                                                      self.unit, self.maximum),
                            capture_output=True, text=True, timeout=15)
    self.assertTrue(verified.exists(), result.stderr)
    self.assertIn(result.returncode, (-signal.SIGKILL, 128 + signal.SIGKILL))

  def test_cleanup_stops_workers_that_create_another_session(self):
    pid_file = self.root / "worker.pid"
    child = ("from pathlib import Path\nimport os, time\n"
             f"Path({str(pid_file)!r}).write_text(str(os.getpid()))\ntime.sleep(60)\n")
    script = self.script(
      f"subprocess.Popen([sys.executable, '-c', {child!r}], start_new_session=True, "
      "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
      f"while not Path({str(pid_file)!r}).exists(): time.sleep(0.01)\n")
    result = subprocess.run(BUILD.memory_scope_command([sys.executable, "-c", script],
                                                      self.unit, self.maximum),
                            capture_output=True, text=True, timeout=15)
    self.assertEqual(result.returncode, 0, result.stderr)
    pid = int(pid_file.read_text())
    BUILD.stop_memory_scope(self.unit)
    state = Path(f"/proc/{pid}/stat")
    self.assertTrue(not state.exists() or state.read_text().split()[2] == "Z",
                    f"Worker {pid} survived scope cleanup")


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

  def test_fetches_from_origin_when_local_source_lacks_revision(self):
    remote = self.root / "remote"
    subprocess.check_call(["git", "clone", "-q", str(self.source), str(remote)])
    self.git(remote, "config", "user.name", "Build check")
    self.git(remote, "config", "user.email", "build@example.invalid")
    (remote / "app.txt").write_text("remote revision\n")
    self.git(remote, "commit", "-qam", "Remote update")
    revision = self.git(remote, "rev-parse", "HEAD").strip()
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, revision, str(remote))
    self.assertEqual((self.target / "app.txt").read_text(), "remote revision\n")
    self.assertEqual((self.source / "app.txt").read_text(), "user's uncommitted edit\n")

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

  def assert_lfs_checkout_hook_is_preserved(self, custom_hooks):
    first, second, binary, updated = self.lfs_cache()
    hooks = self.root / "custom hooks" if custom_hooks else self.target / ".git/hooks"
    hooks.mkdir(exist_ok=True)
    if custom_hooks:
      self.git(self.target, "config", "core.hooksPath", str(hooks))
    original_config = self.git(self.target, "config", "--local", "--list")
    hook = hooks / "post-checkout"
    content = '#!/bin/sh\nprintf failed > checkout-hook-ran\nexit 2\n'
    hook.write_text(content)
    hook.chmod(0o755)
    # Reproduce the hook's failure after checkout has already materialized files.
    with self.assertRaises(subprocess.CalledProcessError):
      self.git(self.target, "-c", "filter.lfs.process=", "-c", "filter.lfs.clean=cat",
               "-c", "filter.lfs.smudge=cat", "-c", "filter.lfs.required=false",
               "checkout", "--detach", first)
    (self.target / "checkout-hook-ran").unlink()
    with patch.object(BUILD, "SOURCE", self.source):
      BUILD.checkout(self.target, self.source, first, self.origin)
      self.assertEqual((self.target / "a.so").read_bytes(), binary)
      BUILD.checkout(self.target, self.source, second, self.origin)
    self.assertEqual((self.target / "a.so").read_bytes(), updated)
    self.assertFalse((self.target / "checkout-hook-ran").exists())
    self.assertEqual(hook.read_text(), content)
    self.assertEqual(self.git(self.target, "config", "--local", "--list"), original_config)

  def test_cached_lfs_post_checkout_hook_does_not_require_host_lfs(self):
    self.assert_lfs_checkout_hook_is_preserved(custom_hooks=False)

  def test_configured_lfs_checkout_hooks_are_preserved(self):
    self.assert_lfs_checkout_hook_is_preserved(custom_hooks=True)

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
