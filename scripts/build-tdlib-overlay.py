#!/usr/bin/env python3
"""Build patched TDLib in a disposable output tree, preserving pinned submodules."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def command(args, log, env=None, cwd=None):
  result = subprocess.run([str(arg) for arg in args], cwd=cwd, env=env,
                          stdout=log, stderr=subprocess.STDOUT)
  if result.returncode:
    raise RuntimeError(f"TDLib command failed ({result.returncode}); see {log.name}")


def properties(path):
  return dict(line.split("=", 1) for line in path.read_text().splitlines()
              if "=" in line and not line.lstrip().startswith("#"))


def tracked_files(source):
  result = subprocess.check_output(["git", "-C", str(source), "ls-files", "-z"])
  return sorted(Path(os.fsdecode(name)) for name in result.split(b"\0") if name)


def fingerprint(source, files, patches, wrapper, version):
  digest = hashlib.sha256()
  for name, path in [(str(p), source / p) for p in files] + [
      ("patch/" + p.name, p) for p in patches] + [
      ("builder", Path(__file__)), ("Client.java", wrapper), ("version", version)]:
    digest.update(name.encode() + b"\0")
    digest.update(path.read_bytes() if path.is_file() else b"missing")
  return digest.hexdigest()[:40]


def generate(args, build, env, cmake, ninja, log):
  root = args.root
  original = root / "tdlib/source/td"
  files = tracked_files(original)
  patches = sorted((root / "patches/tdlib").glob("*.patch"))
  if not patches:
    raise RuntimeError("Missing TDLib overlay patches")
  wrapper = root / "tdlib/src/main/java/org/drinkless/tdlib/Client.java"
  revision = fingerprint(original, files, patches, wrapper, root / "version.properties")
  source = build / "source"
  manifest = build / "generated/revision.json"
  java_dir = build / "generated/java"
  generated_api = java_dir / "org/drinkless/tdlib/TdApi.java"
  if manifest.is_file() and source.is_dir() and generated_api.is_file():
    if json.loads(manifest.read_text()).get("revision") == revision:
      return source, revision
  for tool in ("g++", "gperf", "php"):
    if not shutil.which(tool, path=env["PATH"]):
      raise RuntimeError(f"TDLib code generation requires {tool}; install build-essential gperf php-cli in the build environment")
  for directory in (source, build / "host", java_dir):
    if directory.exists():
      shutil.rmtree(directory)
  source.mkdir(parents=True)
  for relative in files:
    original_file = original / relative
    if not original_file.is_file():
      continue
    target = source / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original_file, target)
  for patch in patches:
    command(["git", "apply", "--check", patch], log, cwd=source)
    command(["git", "apply", patch], log, cwd=source)
  host = build / "host"
  command([cmake, "-S", source / "example/android", "-B", host, "-G", "Ninja",
           "-DTD_GENERATE_SOURCE_FILES=ON", "-DCMAKE_BUILD_TYPE=Release",
           "-DTD_GIT_COMMIT_HASH=" + revision, "-DCMAKE_MAKE_PROGRAM=" + str(ninja)], log, env)
  # Upstream's TLO target has side-effect outputs rather than Ninja BYPRODUCTS.
  # Generate those first, then construct the Java target's dependency graph.
  command([cmake, "--build", host, "--target", "prepare_cross_compiling",
           "--parallel", args.jobs], log, env)
  command([cmake, "--build", host, "--target", "tl_generate_java",
           "--parallel", args.jobs], log, env)
  api = source / "example/android/org/drinkless/tdlib/TdApi.java"
  text = api.read_text().replace("&#039;", "'")
  # Mirror the wrapper install step: NativeLoader owns library initialization.
  text, replacements = re.subn(
    r'    static \{\s*try \{\s*System\.loadLibrary\("tdjni"\);\s*\}'
    r' catch \(UnsatisfiedLinkError e\) \{\s*e\.printStackTrace\(\);\s*\}\s*\}\s*',
    "", text, count=1)
  if replacements != 1 or f'GIT_COMMIT_HASH = "{revision}"' not in text:
    raise RuntimeError("Unexpected generated TDLib initialization or revision")
  generated_api.parent.mkdir(parents=True, exist_ok=True)
  generated_api.write_text(text)
  shutil.copy2(wrapper, generated_api.with_name("Client.java"))
  manifest.parent.mkdir(parents=True, exist_ok=True)
  upstream = subprocess.check_output(["git", "-C", str(original), "rev-parse", "HEAD"], text=True).strip()
  manifest.write_text(json.dumps({"revision": revision, "upstream_revision": upstream,
    "patches": [p.name for p in patches]}, indent=2) + "\n")
  return source, revision


def native(args, build, source, revision, env, cmake, ninja, log):
  ndk = args.sdk / "ndk" / args.ndk
  openssl = args.root / "tdlib/openssl" / args.ndk / args.abi
  crypto = openssl / "lib/libcryptox.so"
  ssl = openssl / "lib/libsslx.so"
  for path in (crypto, ssl):
    if not path.is_file() or path.open("rb").read(4) != b"\x7fELF":
      raise RuntimeError(f"Hydrate the pinned OpenSSL LFS binary: {path}")
  state = build / "native-build" / args.ndk / args.abi
  output = build / "native" / args.ndk / args.abi
  major = int(args.ndk.split(".")[0])
  api = 21 if major >= 26 or args.abi in ("arm64-v8a", "x86_64") else 16
  stl = "c++_shared" if major >= 27 else "c++_static"
  command([cmake, "-S", source / "example/android", "-B", state, "-G", "Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
    "-DCMAKE_MAKE_PROGRAM=" + str(ninja), "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
    "-DANDROID_ABI=" + args.abi, "-DANDROID_PLATFORM=android-" + str(api),
    "-DANDROID_STL=" + stl, "-DTD_GIT_COMMIT_HASH=" + revision,
    "-DOPENSSL_ROOT_DIR=" + str(openssl), "-DOPENSSL_INCLUDE_DIR=" + str(openssl / "include"),
    "-DOPENSSL_CRYPTO_LIBRARY=" + str(crypto), "-DOPENSSL_SSL_LIBRARY=" + str(ssl),
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"], log, env)
  command([cmake, "--build", state, "--target", "tdjni", "--parallel", args.jobs], log, env)
  output.mkdir(parents=True, exist_ok=True)
  shutil.copy2(state / "libtdjni.so", output / "libtdjni.so")
  (output / "revision.json").write_text(json.dumps({"revision": revision,
    "ndk": args.ndk, "abi": args.abi}, indent=2) + "\n")


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("mode", choices=("generate", "native"))
  parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
  parser.add_argument("--sdk", type=Path)
  parser.add_argument("--jobs", type=int, default=2)
  parser.add_argument("--ndk")
  parser.add_argument("--abi", choices=("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
  args = parser.parse_args()
  args.root = args.root.resolve()
  if not args.sdk:
    local = properties(args.root / "local.properties")
    args.sdk = Path(local["sdk.dir"].replace("\\:", ":").replace("\\\\", "\\"))
  args.sdk = args.sdk.resolve()
  if args.jobs <= 0 or (args.mode == "native" and not (args.ndk and args.abi)):
    parser.error("positive --jobs and, for native, --ndk and --abi are required")
  version = properties(args.root / "version.properties")
  tools = args.sdk / "cmake" / version["version.cmake"] / "bin"
  cmake, ninja = tools / "cmake", tools / "ninja"
  if not cmake.is_file() or not ninja.is_file():
    raise RuntimeError("Install the configured Android SDK CMake package")
  env = dict(os.environ)
  env["PATH"] = str(tools) + os.pathsep + env.get("PATH", "")
  build = args.root / "tdlib-overlay/build"
  build.mkdir(parents=True, exist_ok=True)
  logs = build / "logs"
  logs.mkdir(exist_ok=True)
  name = args.mode if args.mode == "generate" else "native-" + args.ndk + "-" + args.abi
  with (build / ".build.lock").open("w") as lock, (logs / (name + ".log")).open("w") as log:
    # Host generation writes into its source tree; serialize it with ABI consumers.
    fcntl.flock(lock, fcntl.LOCK_EX)
    source, revision = generate(args, build, env, cmake, ninja, log)
    if args.mode == "native":
      native(args, build, source, revision, env, cmake, ninja, log)
  print("TDLib overlay " + name + " ready: " + revision)


if __name__ == "__main__":
  try:
    main()
  except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
