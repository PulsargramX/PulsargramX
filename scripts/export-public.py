#!/usr/bin/env python3
"""Export committed shared code into an independent public repository with clean history."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("public_audit", ROOT / "scripts/verify-public.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)
CHANGE_SPEC = importlib.util.spec_from_file_location(
  "public_changelog", ROOT / "scripts/public_changelog.py")
CHANGES = importlib.util.module_from_spec(CHANGE_SPEC)
CHANGE_SPEC.loader.exec_module(CHANGES)


def git(root, *args, input=None):
  environment = dict(os.environ)
  for name in ("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME",
               "GIT_COMMITTER_EMAIL", "EMAIL"):
    environment.pop(name, None)
  return subprocess.check_output(["git", "-C", str(root), *args], input=input,
                                 stderr=subprocess.PIPE, env=environment)


def exported_entries(root, revision):
  entries = git(root, "ls-tree", "-rz", "--full-tree", revision).split(b"\0")
  return {entry.split(b"\t", 1)[1]: entry.split(b"\t", 1)[0]
          for entry in filter(None, entries)
          if not AUDIT.local_path(entry.split(b"\t", 1)[1].decode())}


def source_baseline(root, destination, revision):
  """Keep private revision mapping in .git; migrate old exports by matching their tree."""
  try:
    public_head = git(destination, "rev-parse", "--verify", "HEAD").decode().strip()
  except subprocess.CalledProcessError:
    return None
  state = destination / ".git/public-export-state.json"
  if state.is_file():
    saved = json.loads(state.read_text())
    if saved.get("public") == public_head:
      baseline = saved["source"]
      try:
        git(root, "merge-base", "--is-ancestor", baseline, revision)
        return baseline
      except subprocess.CalledProcessError:
        pass
  # Older exports have no mapping. Compare blobs/gitlinks without copying source history.
  exported = exported_entries(destination, public_head)
  ancestors = git(root, "rev-list", "--first-parent", "--max-count=200", revision).decode().splitlines()
  for candidate in ancestors:
    if exported_entries(root, candidate) == exported:
      return candidate
  return None


def public_changes(root, destination, revision, patterns, client_only=True):
  changed = set(filter(None, git(destination, "diff", "--cached", "--name-only", "-z",
                                 "--no-renames").decode().split("\0")))
  selected = {name for name in changed if not client_only or CHANGES.client_path(name)}
  if not selected:
    return []
  baseline = source_baseline(root, destination, revision)
  if not baseline:
    return summarize_changes(selected)
  commits = git(root, "rev-list", "--first-parent", "--reverse",
                f"{baseline}..{revision}").decode().splitlines()
  changes = []
  for commit in commits:
    paths = set(filter(None, git(root, "diff", "--name-only", "--no-renames", "-z",
                                f"{commit}^", commit).decode().split("\0")))
    public_paths = paths & selected
    if not public_paths:
      continue
    subject = git(root, "show", "-s", "--format=%s", commit).decode().strip()
    mixed = any(AUDIT.local_path(name) for name in paths)
    safe = None if mixed else CHANGES.safe_subject(subject, (*patterns, *AUDIT.PRIVATE_KEYS))
    for change in [safe] if safe else summarize_changes(public_paths):
      if change not in changes:
        changes.append(change)
  return changes or summarize_changes(selected)


def summarize_changes(paths):
  changes = CHANGES.summarize_paths(paths)
  if changes:
    return changes
  if all(name.endswith((".md", ".txt")) for name in paths):
    return ["Update public project documentation"]
  return ["Update public build and release tooling"]


def snapshot_subject(changes):
  suffix = f" (+{len(changes) - 1} more)" if len(changes) > 1 else ""
  title = changes[0]
  available = 72 - len(suffix)
  if len(title) > available:
    title = title[:available - 1].rsplit(" ", 1)[0] + "…"
  return title + suffix


def snapshot(root, destination, revision, patterns=()):
  entries = git(root, "ls-tree", "-rz", "--full-tree", revision).split(b"\0")
  index = []
  files = {}
  for entry in filter(None, entries):
    metadata, raw_name = entry.split(b"\t", 1)
    mode, kind, oid = metadata.decode().split()
    name = raw_name.decode()
    if AUDIT.local_path(name):
      continue
    if name in AUDIT.SECRET_PATHS or name.endswith((".jks", ".keystore", ".p12")):
      raise ValueError("The snapshot contains local or signing configuration")
    AUDIT.scan(raw_name, patterns, "a snapshot filename")
    if kind == "blob":
      data = git(root, "cat-file", "blob", oid)
      AUDIT.scan(data, patterns, name)
      if name.startswith(("app/src/main/", "app/src/public/")):
        AUDIT.scan(data, AUDIT.PRIVATE_KEYS, name)
      files[name] = (mode, data)
    index.append((mode, kind, oid, name))

  destination.mkdir(parents=True, exist_ok=True)
  if not (destination / ".git").is_dir():
    if any(destination.iterdir()):
      raise ValueError("Choose an empty destination for the initial public export")
    git(destination, "init", "-b", "main")
    git(destination, "config", "user.name", "Pulsargram X")
    git(destination, "config", "user.email", "contributors@users.noreply.github.com")
    git(destination, "config", "commit.gpgsign", "false")
    git(destination, "config", "user.useConfigOnly", "true")
    (destination / ".git/public-export-owner").write_text(str(root.resolve()))
  else:
    owner = destination / ".git/public-export-owner"
    if not owner.is_file() or owner.read_text() != str(root.resolve()):
      raise ValueError("The destination is not an export owned by this working repository")
    if git(destination, "status", "--porcelain", "--ignore-submodules=all").strip():
      raise ValueError("Commit or preserve destination edits before exporting again")

  old_names = git(destination, "ls-files", "-z").decode().split("\0")
  new_names = {entry[3] for entry in index}
  for name in filter(None, old_names):
    path = destination / name
    if name not in new_names and (path.is_file() or path.is_symlink()):
      path.unlink()
  for mode, kind, oid, name in index:
    if kind == "commit":
      (destination / name).mkdir(parents=True, exist_ok=True)
  for name, (mode, data) in files.items():
    path = destination / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
      path.unlink()
    if mode == "120000":
      path.unlink(missing_ok=True)
      path.symlink_to(data.decode())
    else:
      path.write_bytes(data)
      path.chmod(0o755 if mode == "100755" else 0o644)
    # Transfer only the selected file blobs, never the private repository's commits.
    git(destination, "hash-object", "-w", "--stdin", input=data)
  git(destination, "read-tree", "--empty")
  for mode, kind, oid, name in index:
    git(destination, "update-index", "--add", "--cacheinfo", f"{mode},{oid},{name}")
  AUDIT.check_source(destination, patterns)
  if git(destination, "diff", "--cached", "--name-only").strip():
    changes = public_changes(root, destination, revision, patterns)
    titles = changes or public_changes(root, destination, revision, patterns, client_only=False)
    message = snapshot_subject(titles) + "\n\n" + CHANGES.HEADING + "\n"
    message += "".join("- " + change + "\n" for change in changes)
    AUDIT.scan(message.encode(), (*patterns, *AUDIT.PRIVATE_KEYS), "public change descriptions")
    git(destination, "commit", "-F", "-", input=message.encode())
  AUDIT.check_history(destination, patterns)
  state = {"source": git(root, "rev-parse", revision).decode().strip(),
           "public": git(destination, "rev-parse", "HEAD").decode().strip()}
  (destination / ".git/public-export-state.json").write_text(json.dumps(state) + "\n")
  return destination


def retain_lfs_bridge(root, destination):
  common = Path(git(root, "rev-parse", "--git-common-dir").decode().strip())
  if not common.is_absolute():
    common = root / common
  bridge = common / "lfs-distrobox"
  if bridge.is_file():
    git(destination, "config", "lfs.storage", str((common / "lfs").resolve()))
    command = shlex.quote(str(bridge.resolve()))
    for key, operation in (("clean", "clean -- %f"), ("smudge", "smudge -- %f"),
                           ("process", "filter-process")):
      git(destination, "config", "filter.lfs." + key, command + " " + operation)
    hook = destination / ".git/hooks/pre-push"
    if not hook.exists():
      hook.write_text("#!/bin/sh\nexec " + command + ' pre-push "$@"\n')
      hook.chmod(0o755)


def main(argv=None):
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--destination", type=Path, default=ROOT / ".public-repo")
  parser.add_argument("--revision", default="HEAD")
  parser.add_argument("--forbidden-file", type=Path, default=ROOT / ".personal/forbidden.txt")
  args = parser.parse_args(argv)
  patterns = args.forbidden_file.read_text().splitlines() if args.forbidden_file.is_file() else []
  destination = args.destination.resolve()
  if destination == ROOT or ROOT.is_relative_to(destination):
    raise ValueError("The export must use a separate directory")
  snapshot(ROOT, destination, args.revision, patterns)
  retain_lfs_bridge(ROOT, destination)
  print(destination)


if __name__ == "__main__":
  try:
    main()
  except (ValueError, OSError, subprocess.CalledProcessError) as error:
    print(f"Public export failed: {error}", file=sys.stderr)
    sys.exit(1)
