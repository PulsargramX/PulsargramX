#!/usr/bin/env python3
"""Prepare repository-local SSH and commit identity for the public GitHub account."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shlex
import subprocess
import sys
import tarfile
import urllib.request


ROOT = Path(__file__).resolve().parent.parent


def git(repository, *args):
  return subprocess.check_output(["git", "-C", str(repository), *args], stderr=subprocess.PIPE)


def noreply_email(username):
  request = urllib.request.Request("https://api.github.com/users/" + username,
                                   headers={"User-Agent": "Pulsargram-X-setup"})
  with urllib.request.urlopen(request, timeout=20) as response:
    account = json.load(response)
  if account.get("login", "").lower() != username.lower() or not isinstance(account.get("id"), int):
    raise ValueError("Cannot read the public GitHub account ID")
  return str(account["id"]) + "+" + account["login"] + "@users.noreply.github.com"


def install_cli(directory):
  executable = directory / "bin/gh"
  if executable.is_file():
    return executable
  architecture = {"x86_64": "amd64", "aarch64": "arm64"}.get(platform.machine())
  if platform.system() != "Linux" or architecture is None:
    raise ValueError("Install GitHub CLI for this platform before using public-gh.sh")

  def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Pulsargram-X-setup"})
    with urllib.request.urlopen(request, timeout=30) as response:
      return response.read()

  release = json.loads(download("https://api.github.com/repos/cli/cli/releases/latest"))
  version = release["tag_name"].removeprefix("v")
  filename = f"gh_{version}_linux_{architecture}.tar.gz"
  assets = {asset["name"]: asset["browser_download_url"] for asset in release["assets"]}
  checksum_file = f"gh_{version}_checksums.txt"
  if filename not in assets or checksum_file not in assets:
    raise ValueError("Cannot find GitHub CLI's official Linux binary and checksums")
  checksums = {}
  for line in download(assets[checksum_file]).decode().splitlines():
    checksum, name = line.split(maxsplit=1)
    checksums[name.lstrip("*")] = checksum
  archive_data = download(assets[filename])
  if hashlib.sha256(archive_data).hexdigest() != checksums.get(filename):
    raise ValueError("GitHub CLI download does not match its published checksum")
  with tarfile.open(fileobj=io.BytesIO(archive_data), mode="r:gz") as archive:
    binary = archive.getmember(f"gh_{version}_linux_{architecture}/bin/gh")
    if not binary.isfile():
      raise ValueError("GitHub CLI archive has no regular executable")
    executable.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = executable.with_suffix(".download")
    with archive.extractfile(binary) as source:
      temporary.write_bytes(source.read())
    temporary.chmod(0o700)
    temporary.replace(executable)
  return executable


def prepare(repository, username=None, email=None, fetch_host_keys=True,
            repository_name="PulsargramX"):
  if username:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}", username):
      raise ValueError("Invalid GitHub username")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", repository_name) or repository_name in (".", ".."):
      raise ValueError("Invalid GitHub repository name")
    email = email or noreply_email(username)
    if not re.fullmatch(r"(?:[0-9]+\+)?" + re.escape(username) +
                        r"@users\.noreply\.github\.com", email, re.I):
      raise ValueError("Use this account's GitHub noreply email")
  elif email:
    raise ValueError("Supply the GitHub username with its noreply email")
  repository = repository.resolve()
  common = Path(git(repository, "rev-parse", "--git-common-dir").decode().strip())
  if not common.is_absolute():
    common = repository / common
  account = common / "public-account"
  account.mkdir(mode=0o700, parents=True, exist_ok=True)
  key = account / "id_ed25519"
  if not key.exists():
    subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "",
                    "-C", "Pulsargram X", "-f", str(key)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
  known_hosts = account / "known_hosts"
  if fetch_host_keys:
    request = urllib.request.Request("https://api.github.com/meta",
                                     headers={"User-Agent": "Pulsargram-X-setup"})
    with urllib.request.urlopen(request, timeout=20) as response:
      keys = json.load(response)["ssh_keys"]
    if not keys or any("\n" in value for value in keys):
      raise ValueError("Cannot read GitHub's published SSH host keys")
    known_hosts.write_text("".join("github.com " + value + "\n" for value in keys))
  config = account / "ssh_config"
  def quoted(path):
    return '"' + str(path).replace('"', '\\"') + '"'
  config.write_text(
    "Host github.com pulsargram-github\n"
    "  HostName github.com\n  User git\n"
    f"  IdentityFile {quoted(key)}\n"
    "  IdentitiesOnly yes\n  IdentityAgent none\n  AddKeysToAgent no\n"
    f"  UserKnownHostsFile {quoted(known_hosts)}\n"
    "  GlobalKnownHostsFile /dev/null\n  StrictHostKeyChecking yes\n"
  )
  for path in (key, config, known_hosts):
    if path.exists():
      path.chmod(0o600)
  git(repository, "config", "--local", "core.sshCommand", "ssh -F " + shlex.quote(str(config)))
  git(repository, "config", "--local", "user.useConfigOnly", "true")
  git(repository, "config", "--local", "commit.gpgsign", "false")
  if username:
    git(repository, "config", "--local", "user.name", "Pulsargram X")
    git(repository, "config", "--local", "user.email", email)
    remote = f"git@github.com:{username}/{repository_name}.git"
    if "origin" in git(repository, "remote").decode().splitlines():
      git(repository, "remote", "set-url", "origin", remote)
    else:
      git(repository, "remote", "add", "origin", remote)
    (account / "repository").write_text(username + "/" + repository_name + "\n")
  return key.with_suffix(".pub")


def main(argv=None):
  os.umask(0o077)
  parser = argparse.ArgumentParser(description=__doc__)
  default_repository = ROOT if (ROOT / ".git/public-account").is_dir() else ROOT / ".public-repo"
  parser.add_argument("--repository", type=Path, default=default_repository)
  parser.add_argument("--username")
  parser.add_argument("--email")
  parser.add_argument("--repository-name", default="PulsargramX")
  parser.add_argument("--install-cli", action="store_true",
                      help="Install the official GitHub CLI only inside this repository")
  args = parser.parse_args(argv)
  key = prepare(args.repository, args.username, args.email, repository_name=args.repository_name)
  if args.install_cli:
    install_cli(key.parent)
  print("Repository-local account setup prepared. Add this public key to the secondary account:")
  print(key)


if __name__ == "__main__":
  try:
    main()
  except (ValueError, OSError, subprocess.CalledProcessError) as error:
    print(f"Account setup failed: {error}", file=sys.stderr)
    sys.exit(1)
