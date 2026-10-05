"""Upload secrets only through the repository's isolated GitHub account."""

import base64
import json
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parent.parent
PUBLIC_PACKAGE = "com.pulsargram.x"


class RepositorySecrets:
  def __init__(self):
    default = ROOT if (ROOT / ".git/public-account").is_dir() else ROOT / ".public-repo"
    directory = Path(os.environ.get("PULSAR_REPO", default)).resolve()
    marker = directory / ".git/public-account/repository"
    if not marker.is_file():
      raise ValueError("Prepare and log into the public account with public-gh.sh first")
    self.repository = marker.read_text().strip()
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", self.repository):
      raise ValueError("Invalid public repository account marker")
    login = self.run("api", "user", "--jq", ".login").decode().strip()
    if login.lower() != self.repository.split("/")[0].lower():
      raise ValueError("The isolated login does not match the public repository owner")

  def run(self, *arguments, input=None):
    try:
      result = subprocess.run([str(ROOT / "scripts/public-gh.sh"), *arguments], input=input,
                              capture_output=True, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired):
      raise ValueError("Cannot reach the isolated GitHub CLI; check login and connection") from None
    if result.returncode:
      raise ValueError("GitHub operation failed; check the isolated account's access")
    return result.stdout

  def names(self):
    result = self.run("secret", "list", "--repo", self.repository, "--json", "name")
    return {item["name"] for item in json.loads(result)}

  def set(self, name, value):
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name) or not value.strip():
      raise ValueError("Secret names must be valid and values cannot be empty")
    self.run("secret", "set", name, "--repo", self.repository, input=value.encode())
    print(f"Configured {name}")


def firebase_value(path):
  data = Path(path).read_bytes()
  configuration = json.loads(data)
  if not isinstance(configuration, dict) or not isinstance(configuration.get("client"), list):
    raise ValueError("Invalid Android Firebase configuration")
  packages = []
  for client in configuration["client"]:
    info = client.get("client_info") if isinstance(client, dict) else None
    android = info.get("android_client_info") if isinstance(info, dict) else None
    if isinstance(android, dict):
      packages.append(android.get("package_name"))
  project = configuration.get("project_info")
  if PUBLIC_PACKAGE not in packages or not isinstance(project, dict) or not project.get("project_id"):
    raise ValueError("Use google-services.json for the public Android app com.pulsargram.x")
  forbidden = ROOT / ".personal/forbidden.txt"
  patterns = os.environ.get("PUBLIC_FORBIDDEN_PATTERNS", "").splitlines()
  if forbidden.is_file():
    patterns.extend(forbidden.read_text().splitlines())
  if any(value.strip().lower().encode() in data.lower() for value in patterns if value.strip()):
    raise ValueError("Firebase configuration contains a private identity pattern")
  return base64.b64encode(data).decode()


def channel_value(value):
  value = value.strip()
  if re.fullmatch(r"-100[0-9]+", value):
    return value
  value = re.sub(r"^(?:https?://)?t\.me/", "", value).removeprefix("@").rstrip("/")
  if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{3,31}", value):
    raise ValueError("Use a channel @username, t.me/username link or numeric -100... ID")
  return "@" + value
