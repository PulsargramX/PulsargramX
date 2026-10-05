#!/usr/bin/env python3
"""Quiet, incremental arm64 release builds in a private checkout."""

import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import signal
import subprocess
import sys
import time


SOURCE = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("release", SOURCE / "scripts/verify-release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class BuildError(Exception):
  pass


def run(*args, cwd=None, env=None):
  try:
    return subprocess.check_output(args, cwd=cwd, env=env, stderr=subprocess.STDOUT)
  except subprocess.CalledProcessError as error:
    raise BuildError(f"{args[0]} failed:\n" + error.output.decode(errors="replace")) from None


def git(root, *args):
  return run("git", "-C", str(root), *args)


def execute(command, **options):
  process = subprocess.Popen(command, start_new_session=True, **options)
  try:
    return process.wait()
  finally:
    # Gradle workers inherit this private process group. Stop them even if the
    # launcher exits successfully, fails, or is interrupted.
    try:
      os.killpg(process.pid, signal.SIGTERM)
      deadline = time.monotonic() + 10
      while time.monotonic() < deadline:
        process.poll()
        try:
          os.killpg(process.pid, 0)
        except ProcessLookupError:
          break
        time.sleep(0.1)
      else:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
      pass
    process.wait()


def uncommitted(root):
  # Host Git LFS may be deliberately unavailable. Hydrated binary inputs are
  # checked against their committed hashes by the worker instead.
  return git(root, "-c", "filter.lfs.process=", "-c", "filter.lfs.clean=cat",
             "-c", "filter.lfs.required=false", "diff", "--binary", "HEAD",
             "--ignore-submodules=all", "--", ".", ":(exclude,attr:filter=lfs)")


def checkout(destination, source, revision, origin):
  source_ready = source.is_dir() and (source / ".git").exists()
  if not (destination / ".git").exists():
    if source_ready:
      run("git", "clone", "--shared", "--no-checkout", str(source), str(destination))
    else:
      run("git", "clone", "--no-checkout", "--filter=blob:none", origin, str(destination))
  else:
    # Never discard manual edits in the script's persistent checkout.
    if uncommitted(destination).strip():
      raise BuildError(f"Build cache contains tracked edits: {destination}")
  git(destination, "remote", "set-url", "origin", origin)
  bridge = Path(git(SOURCE, "rev-parse", "--git-common-dir").decode().strip())
  if not bridge.is_absolute():
    bridge = SOURCE / bridge
  bridge /= "lfs-distrobox"
  if bridge.is_file():
    # Reuse this checkout's existing LFS bridge, without changing its filters.
    command = shlex.quote(str(bridge))
    for key, operation in (("clean", "clean -- %f"), ("smudge", "smudge -- %f"),
                           ("process", "filter-process")):
      git(destination, "config", "filter.lfs." + key, command + " " + operation)
  try:
    git(destination, "cat-file", "-e", revision + "^{commit}")
  except BuildError:
    git(destination, "fetch", "--no-tags", str(source) if source_ready else "origin",
        revision)
  # Materialize LFS pointers without requiring host Git LFS. Hydration happens
  # in the selected build environment, only for CI's three arm64 binaries.
  git(destination, "-c", "filter.lfs.process=", "-c", "filter.lfs.smudge=cat",
      "-c", "filter.lfs.required=false", "checkout", "--detach", revision)


def dependencies(source, destination, revision, nested=False):
  try:
    modules = git(destination, "show", revision + ":.gitmodules").decode()
  except BuildError:
    return
  urls = {}
  path = None
  for line in modules.splitlines():
    if line.lstrip().startswith("[submodule"):
      path = None
    elif "=" in line:
      key, value = (part.strip() for part in line.split("=", 1))
      if key == "path":
        path = value
      elif key == "url" and path:
        urls[path] = value
  for line in git(destination, "ls-tree", "-r", revision).decode().splitlines():
    meta, path = line.split("\t", 1)
    mode, _, commit = meta.split()
    if mode != "160000":
      continue
    if nested and path not in ("source/td", "source/openssl", "jni/leveldb", "jni/jni-utils"):
      continue
    checkout(destination / path, source / path, commit, urls[path])
    if not nested and path in ("tdlib", "vkryl/leveldb"):
      dependencies(source / path, destination / path, commit, nested=True)


def java21():
  candidates = [Path(os.environ.get("JAVA_HOME", "/nonexistent"))]
  executable = shutil.which("java")
  if executable:
    candidates.append(Path(executable).resolve().parent.parent)
  candidates += sorted(Path("/usr/lib/jvm").glob("*"))
  for candidate in candidates:
    if not (candidate / "bin/java").is_file():
      continue
    version = run(str(candidate / "bin/java"), "-version").decode()
    if re.search(r'version "21[.\"]', version):
      return candidate
  return None


def invalid_binaries(project, names):
  invalid = []
  for name in names:
    pointer = git(project / "tdlib", "show", "HEAD:" + name)
    match = re.search(rb"oid sha256:([0-9a-f]{64})", pointer)
    path = project / "tdlib" / name
    if not path.is_file() or not match or \
        hashlib.sha256(path.read_bytes()).hexdigest().encode() != match[1]:
      invalid.append(name)
  return invalid


def apply_working_patch(project, saved_patch, content):
  candidate = saved_patch.with_suffix(".candidate")
  try:
    candidate.write_bytes(content)
    git(project, "apply", "--index", str(candidate))
    saved_patch.write_bytes(uncommitted(project))
  finally:
    candidate.unlink(missing_ok=True)


def report_failure(error, log):
  # Keep the current error visible even when the log is absent or from an older run.
  message = f"error: {error}"
  tail = ""
  if log.is_file():
    tail = "\n".join(log.read_text(errors="replace").splitlines()[-70:])
  props = {}
  try:
    props = RELEASE.read_properties(SOURCE / "local.properties")
    signing = Path(props.get("keystore.file", ""))
    if signing.is_file():
      props.update(RELEASE.read_properties(signing))
  except (OSError, ValueError):
    pass
  for key, value in props.items():
    if value and any(word in key for word in ("password", "api_", "api.", "token")):
      message = message.replace(value, "<redacted>")
      tail = tail.replace(value, "<redacted>")
  print(message, file=sys.stderr)
  if tail:
    print(tail, file=sys.stderr)
  if log.is_file():
    print(f"Build log: {log}", file=sys.stderr)


def worker(project, cache, edition):
  jdk = java21()
  if not jdk:
    raise BuildError("JDK 21 is required; install it in the selected build environment.")
  os.environ["JAVA_HOME"] = str(jdk)
  os.environ["PATH"] = str(jdk / "bin") + os.pathsep + os.environ["PATH"]
  local = RELEASE.read_properties(project / "local.properties")
  version = RELEASE.read_properties(project / "version.properties")
  for key in ("app.experimental", "app.disable_signing", "app.dontobfuscate"):
    if local.get(key, "false").lower() == "true":
      raise BuildError(f"CI release builds require {key}=false.")
  if local.get("tgx.extension", "none") != "none":
    raise BuildError("CI release builds require tgx.extension=none.")
  if int(local.get("app.version", "0")) > 0:
    raise BuildError("Remove app.version override to build the committed CI version.")
  sdk = Path(local["sdk.dir"])
  if not sdk.is_absolute():
    raise BuildError("Set sdk.dir in local.properties to an absolute Android SDK path.")
  if not any((sdk / "platforms").glob("android-" + version["version.sdk_compile"] + "*")):
    raise BuildError("Install SDK platform " + version["version.sdk_package"] + ".")
  for tool in ("git", "make"):
    if not shutil.which(tool):
      raise BuildError(f"Required build tool is missing: {tool}")
  for directory in (sdk / "ndk" / version["version.ndk_primary"],
                    sdk / "cmake" / version["version.cmake"],
                    sdk / "build-tools" / version["version.build_tools"]):
    if not directory.is_dir():
      raise BuildError(f"Required Android SDK component is missing: {directory}")
  # Signing paths must survive relocation; reference existing credentials only.
  signing_property = "keystore.file" if edition == "personal" else "app.public.keystore_file"
  signing = Path(local.get(signing_property, ""))
  if not signing.is_absolute() or not signing.is_file():
    raise BuildError("Set the selected edition's signing properties to an existing absolute path.")
  key = RELEASE.read_properties(signing)
  if not Path(key.get("keystore.file", "")).is_absolute():
    raise BuildError("Use an absolute keystore.file path in the signing properties.")
  build_tools, certificate = RELEASE.local_signing_config(project, edition)
  if shutil.which("ccache"):
    os.environ["CMAKE_C_COMPILER_LAUNCHER"] = shutil.which("ccache")
    os.environ["CMAKE_CXX_COMPILER_LAUNCHER"] = shutil.which("ccache")
    os.environ.setdefault("CCACHE_MAXSIZE", "512M")
  ndk = version["version.ndk_primary"]
  binaries = [f"src/main/libs/{ndk}/libs/arm64-v8a/libtdjni.so",
              f"openssl/{ndk}/arm64-v8a/lib/libcryptox.so",
              f"openssl/{ndk}/arm64-v8a/lib/libsslx.so"]
  if invalid_binaries(project, binaries):
    if not shutil.which("git-lfs"):
      raise BuildError("Git LFS is required in the build environment to hydrate TDLib.")
    git(project / "tdlib", "lfs", "pull", "--include=" + ",".join(binaries))
    invalid = invalid_binaries(project, binaries)
    if invalid:
      raise BuildError("TDLib files do not match committed LFS hashes: " + ", ".join(invalid))
  os.environ["TGX_GRADLE_PROJECT_DIR"] = str(project)
  common = ["--no-parallel", "--max-workers=1", "--stacktrace",
            "-Pkotlin.compiler.execution.strategy=in-process", "-Ptgx.nativeJobs=2",
            "-Pclient.edition=" + edition]
  logs = cache / "logs"
  logs.mkdir(exist_ok=True)
  variant = edition + "LatestArm64Release"
  task_variant = variant[0].upper() + variant[1:]
  for stage, task, heap in (("native", ":app:externalNativeBuild" + task_variant, "2g"),
                            ("APK", ":app:assemble" + task_variant, "4g")):
    print(f"Building {stage}", flush=True)
    os.environ["TGX_GRADLE_LOG_FILE"] = str(logs / (stage.lower() + ".log"))
    status = execute([str(SOURCE / "scripts/gradle.sh"), task, *common,
      f"-Dorg.gradle.jvmargs=-Xmx{heap} -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"],
      cwd=project)
    if status:
      # The native library tasks also retain configure/compiler logs.
      for log in (project / "app/build/generated/tgx").glob("*-build/**/build.log"):
        print(f"Native log: {log}")
        print("\n".join(log.read_text(errors="replace").splitlines()[-15:]))
      raise BuildError(f"{stage} build failed (exit {status}); log: {os.environ['TGX_GRADLE_LOG_FILE']}")
  apks = list((project / "app/build/outputs/apk" / (edition + "LatestArm64") / "release").glob("*.apk"))
  if len(apks) != 1:
    raise BuildError("Expected exactly one arm64 release APK.")
  subprocess.run([sys.executable, str(SOURCE / "scripts/verify-release.py"), str(apks[0]),
    "--project-root", str(project), "--build-tools", str(build_tools),
    "--edition", edition, "--variant", variant,
    "--expected-package", local["app.id"] if edition == "personal" else "com.pulsargram.x",
    "--expected-certificate", certificate], check=True)
  (cache / "result.json").write_text(json.dumps({"apk": str(apks[0])}))


def main():
  parser = argparse.ArgumentParser(description=__doc__, epilog=(
    "Success prints only the absolute APK path. Builds committed HEAD by default; "
    "--working-tree includes tracked edits (stage new files first). "
    "Private cache and logs: build/local-apk, or TGX_BUILD_CACHE_DIR. "
    "Requires configured local.properties, Android SDK, and JDK 21 on the host "
    "or in the configured development container."
  ))
  parser.add_argument("--edition", choices=("personal", "public"), default="personal",
                      help="personal (default, local overlay) or public")
  parser.add_argument("--revision", default="HEAD", help="committed revision (default: HEAD)")
  parser.add_argument("--working-tree", action="store_true",
                      help="also include tracked, uncommitted edits; requires HEAD")
  parser.add_argument("--output-dir", type=Path, default=SOURCE / "builds")
  parser.add_argument("--container", default="auto",
                      help="auto, host, or a Distrobox name (default: auto)")
  parser.add_argument("--worker", nargs=2, metavar=("PROJECT", "CACHE"), help=argparse.SUPPRESS)
  args = parser.parse_args()
  os.umask(0o077)
  if args.worker:
    worker(*(Path(value) for value in args.worker), args.edition)
    return
  if not (SOURCE / "local.properties").is_file():
    raise BuildError("Configure local.properties with the SDK, API credentials and release key.")
  revision = git(SOURCE, "rev-parse", "--verify", args.revision + "^{commit}").decode().strip()
  if args.working_tree and revision != git(SOURCE, "rev-parse", "HEAD").decode().strip():
    raise BuildError("--working-tree requires the current HEAD revision.")
  if args.edition == "personal" and not (SOURCE / ".personal/java").is_dir():
    raise BuildError("The personal edition requires this PC's .personal source overlay.")
  cache = Path(os.environ.get("TGX_BUILD_CACHE_DIR", SOURCE / "build/local-apk")).resolve() / args.edition
  cache.mkdir(parents=True, exist_ok=True)
  with (cache / "lock").open("w") as lock:
    try:
      fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
      raise BuildError("Another APK build is already using this cache.")
    log = cache / "build.log"
    try:
      project = cache / "checkout"
      owner = cache / "owner"
      identity = str(SOURCE)
      if project.exists() and (not owner.is_file() or owner.read_text() != identity):
        raise BuildError("Cache is not owned by this script; choose another TGX_BUILD_CACHE_DIR.")
      owner.write_text(identity)
      saved_patch = cache / "working.patch"
      if saved_patch.is_file():
        # Staging the patch in the cache keeps new tracked files visible to this guard.
        current = uncommitted(project)
        if current != saved_patch.read_bytes():
          raise BuildError("Build cache edits changed since its previous working-tree build.")
        git(project, "apply", "--reverse", "--index", str(saved_patch))
        saved_patch.unlink()
      with log.open("wb") as captured:
        origin = git(SOURCE, "remote", "get-url", "origin").decode().strip()
        checkout(project, SOURCE, revision, origin)
        dependencies(SOURCE, project, revision)
        configuration = project / "local.properties"
        if configuration.exists() and not configuration.is_symlink():
          raise BuildError("Cache contains an unexpected local.properties file.")
        if configuration.is_symlink():
          configuration.unlink()
        configuration.symlink_to(SOURCE / "local.properties")
        if args.edition == "personal":
          overlay = project / ".personal"
          if overlay.exists() and not overlay.is_symlink():
            raise BuildError("Cache contains an unexpected personal source directory.")
          if overlay.is_symlink():
            overlay.unlink()
          overlay.symlink_to(SOURCE / ".personal", target_is_directory=True)
          firebase = project / "app/src/personal/google-services.json"
          firebase.parent.mkdir(parents=True, exist_ok=True)
          if not firebase.is_symlink() and firebase.exists():
            raise BuildError("Cache contains unexpected personal Firebase configuration.")
          if firebase.is_symlink():
            firebase.unlink()
          firebase.symlink_to("../../../.personal/google-services.json")
        if args.edition == "public":
          firebase_source = SOURCE / "app/src/public/google-services.json"
          if not firebase_source.is_file():
            raise BuildError("Configure app/src/public/google-services.json for public local builds.")
          firebase = project / "app/src/public/google-services.json"
          firebase.parent.mkdir(parents=True, exist_ok=True)
          if firebase.exists() and not firebase.is_symlink():
            raise BuildError("Cache contains unexpected public Firebase configuration.")
          if firebase.is_symlink():
            firebase.unlink()
          firebase.symlink_to(firebase_source)
        patch = uncommitted(SOURCE)
        if args.working_tree and patch:
          apply_working_patch(project, saved_patch, patch)
        command = [sys.executable, str(Path(__file__).resolve()), "--edition", args.edition, "--worker",
                   str(project), str(cache)]
        if args.container != "host" and (args.container != "auto" or not java21()):
          if not shutil.which("distrobox"):
            raise BuildError("JDK 21 is missing on the host and Distrobox is unavailable.")
          container_file = SOURCE / ".personal/container"
          default_container = (container_file.read_text().strip() if container_file.is_file()
                               else os.environ.get("TGX_BUILD_CONTAINER", "client-dev"))
          name = default_container if args.container == "auto" else args.container
          command = ["distrobox", "enter", name, "--", "python3", *command[1:]]
        status = execute(command, cwd=project, stdout=captured, stderr=subprocess.STDOUT)
        if status:
          raise BuildError(f"Release build failed (exit {status}).")
      apk = Path(json.loads((cache / "result.json").read_text())["apk"])
      output = args.output_dir.resolve()
      output.mkdir(parents=True, exist_ok=True)
      suffix = revision[:8] + ("-working" if args.working_tree and patch else "")
      destination = output / f"{apk.stem}-{suffix}.apk"
      temporary = destination.with_suffix(".apk.tmp")
      shutil.copyfile(apk, temporary)
      os.replace(temporary, destination)
      mapping = project / "app/build/outputs/mapping" / (args.edition + "LatestArm64Release") / "mapping.txt"
      if mapping.exists():
        shutil.copyfile(mapping, destination.with_suffix(".mapping.txt"))
      digest = hashlib.sha256(destination.read_bytes()).hexdigest()
      destination.with_suffix(".apk.sha256").write_text(digest + "  " + destination.name + "\n")
      print(destination)
    except (BuildError, subprocess.CalledProcessError, OSError) as error:
      report_failure(error, log)
      raise SystemExit(1)


if __name__ == "__main__":
  def interrupted(signum, frame):
    raise KeyboardInterrupt

  signal.signal(signal.SIGTERM, interrupted)
  try:
    main()
  except (BuildError, subprocess.CalledProcessError, OSError, KeyError, ValueError) as error:
    print(f"error: {error}", file=sys.stderr)
    raise SystemExit(1)
  except KeyboardInterrupt:
    print("error: build interrupted", file=sys.stderr)
    raise SystemExit(130)
