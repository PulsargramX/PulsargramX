#!/usr/bin/env python3
"""Audit the public source snapshot and APK without displaying matched private text."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent
LOCAL_PATHS = (".personal/", ".public-repo/", ".kiro/", ".codex/", "AGENTS.md", "plan.md", "entities.json",
               "mempalace.yaml", "app/mempalace.yaml", "scripts/mempalace.yaml", "app/latestArm64/")
PRIVATE_KEYS = ("allow_save_secret_media", "view_without_timer", "show_burn_button")
SECRET_PATHS = ("local.properties", "keystore.properties", "release.keystore",
                ".github/github-secrets.txt", "app/google-services.json",
                "app/src/public/google-services.json")


def local_path(name):
  return any(name == item or (item.endswith("/") and name.startswith(item))
             for item in LOCAL_PATHS)


def needles(patterns):
  return [encoded.lower() for pattern in patterns if pattern.strip()
          for encoded in (pattern.strip().encode("utf-8"), pattern.strip().encode("utf-16-le"))]


def scan(data, patterns, location):
  lower = data.lower()
  if any(pattern in lower for pattern in needles(patterns)):
    raise ValueError(f"Private identity text found in {location}")


def scan_elf(data, patterns, location):
  """Scan ELF bytes while avoiding matches split across Itanium ABI name boundaries."""
  lower = data.lower()
  length_matches = list(re.finditer(rb"([0-9]+)", lower))
  for pattern in needles(patterns):
    start = 0
    while (position := lower.find(pattern, start)) >= 0:
      end = position + len(pattern)
      split_name = False
      for length_match in length_matches:
        name_start = length_match.end()
        name_end = name_start + int(length_match.group(1))
        if name_start < position < name_end < end:
          mangled_start = lower.rfind(b"_z", max(0, length_match.start() - 256),
                                      length_match.start())
          name = lower[name_start:name_end]
          next_code = lower[name_end:name_end + 1]
          mangled_prefix = lower[mangled_start:name_start] if mangled_start >= 0 else b""
          if (mangled_start >= 0 and re.fullmatch(rb"[a-z0-9_]+", mangled_prefix) and
              len(name) == name_end - name_start and
              re.fullmatch(rb"[a-z_][a-z0-9_]*", name) and
              next_code in b"eijmnstuvwxyz"):
            split_name = True
            break
      if not split_name:
        raise ValueError(f"Private identity text found in {location}")
      start = end


def check_history(root, patterns=()):
  head = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "HEAD"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
  if head.returncode:
    return  # The initial snapshot is audited before its first commit.
  history = subprocess.check_output([
    "git", "-C", str(root), "log", "--all", "--format=%an%x00%ae%x00%cn%x00%ce%x00%B%x00"
  ])
  scan(history, patterns, "public commit authors, emails or messages")


def check_source(root, patterns=(), export=False):
  entries = subprocess.check_output(["git", "-C", str(root), "ls-files", "--stage", "-z"]).decode().split("\0")
  checked = 0
  for entry in filter(None, entries):
    metadata, name = entry.split("\t", 1)
    if metadata.startswith("160000 "):
      continue
    path = root / name
    if export and not path.exists() and not path.is_symlink():
      continue
    if local_path(name):
      if export:
        continue
      raise ValueError(f"Local-only file tracked in public source: {name}")
    if name in SECRET_PATHS or name.endswith((".jks", ".keystore", ".p12")):
      raise ValueError(f"Signing or local configuration tracked in public source: {name}")
    scan(name.encode(), patterns, "a tracked filename")
    path = root / name
    if path.is_symlink():
      data = os.readlink(path).encode()
    elif path.is_file():
      data = path.read_bytes()
    else:
      continue  # Gitlinks are kept at their committed upstream revisions.
    scan(data, patterns, name)
    if name.startswith(("app/src/main/", "app/src/public/")):
      scan(data, PRIVATE_KEYS, name)
    checked += 1
  policy = root / "app/src/public/java/org/thunderdog/challegram/config/ClientPolicy.java"
  if not policy.is_file():
    raise ValueError("Missing public content policy")
  if not export:
    check_history(root, patterns)
  print(f"Verified {checked} public source files")


def check_apk(apk, build_tools, patterns=()):
  badging = subprocess.check_output([str(build_tools / "aapt2"), "dump", "badging", str(apk)])
  if b"package: name='com.pulsargram.x'" not in badging or b"Pulsargram X" not in badging:
    raise ValueError("Public APK has an unexpected application ID or name")
  certificate = subprocess.check_output([
    str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)
  ])
  scan(badging + certificate, patterns, "public APK metadata or signing certificate")
  with ZipFile(apk) as archive:
    for entry in archive.infolist():
      scan(entry.filename.encode(), patterns, "an APK entry name")
      data = archive.read(entry)
      if data.startswith(b"\x7fELF"):
        scan_elf(data, patterns, entry.filename)
      else:
        scan(data, patterns, entry.filename)
      scan(data, PRIVATE_KEYS, entry.filename)
  print("Verified public APK identity and absence of personal settings")


def main(argv=None):
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--source", action="store_true")
  parser.add_argument("--export", action="store_true",
                      help="Exclude local-only metadata when checking the private working repository")
  parser.add_argument("--project-root", type=Path, default=ROOT)
  parser.add_argument("--apk", type=Path)
  parser.add_argument("--build-tools", type=Path)
  parser.add_argument("--forbidden-file", type=Path,
                      help="Local-only identity patterns, one per line; values are never printed")
  args = parser.parse_args(argv)
  patterns = os.environ.get("PUBLIC_FORBIDDEN_PATTERNS", "").splitlines()
  if args.forbidden_file:
    patterns.extend(args.forbidden_file.read_text().splitlines())
  if not args.source and not args.apk:
    parser.error("Choose --source and/or --apk")
  if args.source:
    check_source(args.project_root.resolve(), patterns, args.export)
  if args.apk:
    if not args.build_tools:
      parser.error("--apk requires --build-tools")
    check_apk(args.apk, args.build_tools, patterns)


if __name__ == "__main__":
  try:
    main()
  except (ValueError, OSError, subprocess.CalledProcessError) as error:
    print(f"Public audit failed: {error}", file=sys.stderr)
    sys.exit(1)
