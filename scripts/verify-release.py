#!/usr/bin/env python3
"""Check the arm64 release, TDLib revision and profile inputs before publishing it."""

import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile


def require(condition, message):
  if not condition:
    raise ValueError(message)


def read_properties(path):
  """Read the UTF-8 Java Properties files used by Gradle, without exposing values."""
  def unescape(value):
    result = []
    index = 0
    while index < len(value):
      character = value[index]
      index += 1
      if character == "\\" and index < len(value):
        character = value[index]
        index += 1
        if character == "u":
          code = value[index:index + 4]
          require(len(code) == 4 and re.fullmatch(r"[0-9a-fA-F]{4}", code),
                  f"Invalid Unicode escape in properties file: {path}")
          character = chr(int(code, 16))
          index += 4
        else:
          character = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(character, character)
      result.append(character)
    return "".join(result).encode("utf-16", "surrogatepass").decode("utf-16", "surrogatepass")

  logical_lines = []
  pending = ""
  continuing = False
  for line in re.split(r"\r\n|\r|\n", Path(path).read_text(encoding="utf-8")):
    line = line.lstrip(" \t\f")
    if not continuing and (not line or line[0] in "#!"):
      continue
    pending += line
    backslashes = len(pending) - len(pending.rstrip("\\"))
    continuing = backslashes % 2 == 1
    if continuing:
      pending = pending[:-1]
    else:
      logical_lines.append(pending)
      pending = ""
  if pending:
    logical_lines.append(pending)

  properties = {}
  for line in logical_lines:
    end = 0
    escaped = False
    while end < len(line):
      character = line[end]
      if not escaped and character in "=: \t\f":
        break
      escaped = character == "\\" and not escaped
      end += 1
    start = end
    while start < len(line) and line[start] in " \t\f":
      start += 1
    if start < len(line) and line[start] in "=:":
      start += 1
    while start < len(line) and line[start] in " \t\f":
      start += 1
    properties[unescape(line[:end])] = unescape(line[start:])
  return properties


def local_signing_config(root, edition="personal"):
  root = Path(root).resolve()
  local = read_properties(root / "local.properties")
  version = read_properties(root / "version.properties")
  key = "keystore.file" if edition == "personal" else "app.public.keystore_file"
  require(local.get(key), "Configure a separate signing key for the selected edition")
  signing = read_properties(root / local[key])
  keystore = root / signing["keystore.file"]
  require(keystore.is_file(), "Release signing keystore does not exist")
  build_tools = root / local["sdk.dir"] / "build-tools" / version["version.build_tools"]
  environment = os.environ.copy()
  environment["CLIENT_KEYSTORE_PASSWORD"] = signing["keystore.password"]
  try:
    certificate = subprocess.run(
      ["keytool", "-exportcert", "-keystore", str(keystore), "-alias", signing["key.alias"],
       "-storepass:env", "CLIENT_KEYSTORE_PASSWORD"],
      cwd=root, env=environment, capture_output=True, check=True,
    ).stdout
  except subprocess.CalledProcessError:
    raise ValueError(
      "Cannot read release signing certificate; check signing configuration"
    ) from None
  require(certificate, "Release signing keystore returned no certificate")
  return build_tools, hashlib.sha256(certificate).hexdigest()


def output(*args):
  return subprocess.check_output(args, text=True)


def signer(build_tools, apk):
  report = output(str(build_tools / "apksigner"), "verify", "--print-certs", str(apk))
  certificates = set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", report))
  require(len(certificates) == 1, "Expected exactly one APK signing certificate")
  return certificates.pop()


def manifest(build_tools, apk):
  report = output(str(build_tools / "aapt2"), "dump", "badging", str(apk))
  package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", report, re.M)
  require(package, "Cannot read APK package and version code")
  require("application-debuggable" not in report, "Release APK must not be debuggable")
  return package.group(1), int(package.group(2))


def only_file(directory, pattern):
  matches = list(directory.rglob(pattern))
  require(len(matches) == 1, f"Expected one {pattern} under {directory}")
  return matches[0]


def verify_tdlib(root, apk):
  source = root / "tdlib-overlay/build/generated/java/org/drinkless/tdlib/TdApi.java"
  require(source.is_file(), "Generate the TDLib overlay before verifying the APK")
  match = re.search(r'\bGIT_COMMIT_HASH\s*=\s*"([0-9a-f]{40})"', source.read_text())
  require(match, "Cannot read the TDLib revision from TdApi.java")
  expected = match.group(1)
  with ZipFile(apk) as archive:
    native = archive.read("lib/arm64-v8a/libtdjni.so")
  require(native.startswith(b"\x7fELF"), "Missing TDLib native binary")
  # TDLib embeds the revision as a C string and compares it with TdApi.java on load.
  require(b"\0" + expected.encode("ascii") + b"\0" in native,
          f"Packaged libtdjni.so does not match TdApi.java ({expected}); "
          "the app would abort on startup")
  print(f"Verified packaged TDLib revision: {expected}")


def verify_profiles(root, apk, variant="publicLatestArm64Release"):
  intermediates = root / "app/build/intermediates"
  saved = root / "app/src/main/generated/baselineProfiles"
  for kind, name in [("art", "baseline-prof.txt"), ("startup", "startup-prof.txt")]:
    merged = only_file(intermediates / f"merged_{kind}_profile/{variant}", name)
    app_rules = {
      line for line in (saved / name).read_text().splitlines()
      if "Lorg/thunderdog/challegram/" in line
    }
    require(app_rules, f"Saved {kind} profile contains no app rules")
    missing = app_rules - set(merged.read_text().splitlines())
    require(not missing, f"Release {kind} profile is missing {len(missing)} app rules")
    print(f"Verified {len(app_rules)} app rules in the {kind} profile")

  with ZipFile(apk) as archive:
    for kind, name, magic in [
      ("binary_art_profile", "baseline.prof", b"pro\0"),
      ("binary_art_profile_metadata", "baseline.profm", b"prm\0"),
    ]:
      packaged = archive.read(f"assets/dexopt/{name}")
      compiled = only_file(intermediates / kind / variant, name).read_bytes()
      require(packaged.startswith(magic), f"Invalid packaged {name}")
      require(packaged == compiled, f"APK contains a stale {name}")
      if name == "baseline.prof":
        require(len(packaged) <= 1_500_000, "Baseline Profile exceeds Android's size limit")

    for library in ["libtgxjni.so", "libtgcallsjni.so", "libtdjni.so",
                    "libleveldbjni.so", "libcryptox.so", "libsslx.so"]:
      with archive.open(f"lib/arm64-v8a/{library}") as native:
        require(native.read(4) == b"\x7fELF", f"Missing native binary: {library}")


def main(argv=None):
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("apk", type=Path)
  parser.add_argument("--build-tools", type=Path)
  parser.add_argument("--expected-certificate")
  parser.add_argument("--expected-package")
  parser.add_argument("--edition", choices=("public", "personal"), default="public")
  parser.add_argument("--variant")
  parser.add_argument("--project-root", type=Path,
                      default=Path(__file__).resolve().parent.parent)
  parser.add_argument("--local-signing-config", action="store_true",
                      help="Read SDK and release signing configuration from the project")
  parser.add_argument("--previous-apk", type=Path)
  args = parser.parse_args(argv)
  root = args.project_root.resolve()
  if args.local_signing_config:
    local_build_tools, local_certificate = local_signing_config(root, args.edition)
    args.build_tools = args.build_tools or local_build_tools
    args.expected_certificate = args.expected_certificate or local_certificate
  if args.build_tools is None or args.expected_certificate is None:
    parser.error("--build-tools and --expected-certificate are required unless "
                 "--local-signing-config is used")

  certificate = signer(args.build_tools, args.apk)
  require(certificate == args.expected_certificate.lower(), "APK signing certificate changed")
  package, version = manifest(args.build_tools, args.apk)
  expected_package = args.expected_package or ("com.pulsargram.x" if args.edition == "public"
    else read_properties(root / "local.properties")["app.id"])
  require(package == expected_package, "Release package differs from the selected edition")
  properties = read_properties(root / "version.properties")
  require(version // 1000 == int(properties["version.app"]), "Incorrect release version code")

  if args.previous_apk:
    old_package, old_version = manifest(args.build_tools, args.previous_apk)
    require(package == old_package, "Package differs from the previous APK")
    require(version >= old_version, "Version code would require a downgrade")
    require(certificate == signer(args.build_tools, args.previous_apk),
            "Signing certificate differs from the previous APK")
    print(f"Update identity verified: {old_version} -> {version}")

  verify_tdlib(root, args.apk)
  verify_profiles(root, args.apk, args.variant or args.edition + "LatestArm64Release")
  if args.edition == "public":
    audit_command = [sys.executable, str(root / "scripts/verify-public.py"),
                     "--apk", str(args.apk), "--build-tools", str(args.build_tools)]
    forbidden_file = root / ".personal/forbidden.txt"
    if forbidden_file.is_file():
      audit_command.extend(["--forbidden-file", str(forbidden_file)])
    subprocess.run(audit_command, check=True)
  print(f"Verified signed release: {package}, version code {version}")


if __name__ == "__main__":
  try:
    main()
  except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
    print(f"Release verification failed: {error}", file=sys.stderr)
    sys.exit(1)
