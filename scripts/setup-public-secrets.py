#!/usr/bin/env python3
"""Configure missing public build and Telegram secrets without displaying credentials."""

import argparse
import getpass
import importlib.util
from pathlib import Path
import re
import sys

from public_secrets import RepositorySecrets, channel_value, firebase_value


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("public_signing", ROOT / "scripts/setup-public-signing.py")
SIGNING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIGNING)
PROMPTS = {
  "TELEGRAM_API_ID": "Public app Telegram API ID (my.telegram.org/apps): ",
  "TELEGRAM_API_HASH": "Public app Telegram API hash (hidden): ",
  "TELEGRAM_BOT_TOKEN": "New Telegram bot token (hidden): ",
  "TELEGRAM_CHANNEL_ID": "New channel @username, t.me link or numeric -100... ID: ",
  "FIREBASE_CONFIG_BASE64": "Public app google-services.json path (com.pulsargram.x): ",
}
HIDDEN = {"TELEGRAM_API_ID", "TELEGRAM_API_HASH", "TELEGRAM_BOT_TOKEN"}


def validate(name, value):
  value = value.strip()
  if name == "FIREBASE_CONFIG_BASE64":
    return firebase_value(Path(value).expanduser())
  if name == "TELEGRAM_CHANNEL_ID":
    return channel_value(value)
  patterns = {
    "TELEGRAM_API_ID": r"[1-9][0-9]*",
    "TELEGRAM_API_HASH": r"[A-Fa-f0-9]{32}",
    "TELEGRAM_BOT_TOKEN": r"[0-9]+:[A-Za-z0-9_-]{20,}",
  }
  if not re.fullmatch(patterns[name], value):
    raise ValueError(f"Invalid format for {name}")
  return value


def configure(account, replace=()):
  existing = account.names() - set(replace)
  # Signing stays stable across updates; prepare() always preserves an existing key.
  if not {"KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"} <= existing:
    for name, value in SIGNING.signing_values(SIGNING.prepare()).items():
      account.set(name, value)
      existing.add(name)
  if "PUBLIC_FORBIDDEN_PATTERNS" not in existing:
    forbidden = ROOT / ".personal/forbidden.txt"
    if not forbidden.is_file():
      raise ValueError("Prepare the local identity pattern file before enabling public builds")
    account.set("PUBLIC_FORBIDDEN_PATTERNS", forbidden.read_text())
  print(f"Public repository: {account.repository}")
  print("Existing secrets are kept. Leave a prompt blank to skip it for now.")
  for name, prompt in PROMPTS.items():
    if name in existing:
      continue
    while True:
      value = getpass.getpass(prompt) if name in HIDDEN else input(prompt)
      if not value.strip():
        break
      try:
        prepared = validate(name, value)
      except (ValueError, OSError):
        print(f"Invalid input for {name}; check its format or file and try again.")
        continue
      account.set(name, prepared)
      break
  missing = set(PROMPTS) | {
    "KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD",
    "PUBLIC_FORBIDDEN_PATTERNS",
  }
  missing -= account.names()
  print("Still needed: " + ", ".join(sorted(missing)) if missing else "All public secrets configured.")


def main(argv=None):
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--replace", nargs="+", choices=tuple(PROMPTS), default=(),
                      help="Prompt again for selected existing secrets")
  args = parser.parse_args(argv)
  if not sys.stdin.isatty():
    raise ValueError("Run this helper in your terminal so secret input can stay hidden")
  configure(RepositorySecrets(), args.replace)


if __name__ == "__main__":
  try:
    main()
  except (ValueError, OSError) as error:
    print(f"Public secret setup failed: {error}", file=sys.stderr)
    sys.exit(1)
  except (KeyboardInterrupt, EOFError):
    print("\nStopped; previously uploaded secrets are retained.", file=sys.stderr)
    sys.exit(130)
