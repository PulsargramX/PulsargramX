#!/usr/bin/env python3
"""Create a neutral public signing key; optionally configure secrets through the isolated CLI."""

import argparse
import base64
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import sys

from public_secrets import RepositorySecrets, firebase_value


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("signing_properties", ROOT / "scripts/verify-release.py")
PROPERTIES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROPERTIES)


def prepare():
  directory = ROOT / ".personal/public-signing"
  directory.mkdir(mode=0o700, parents=True, exist_ok=True)
  configuration = directory / "keystore.properties"
  keystore = directory / "release.keystore"
  if configuration.is_file() and keystore.is_file():
    return configuration
  if configuration.exists() or keystore.exists():
    raise ValueError("Public signing setup is incomplete; preserve and inspect existing files first")
  password = secrets.token_hex(32)
  environment = dict(os.environ, PULSAR_SIGNING_PASSWORD=password)
  subprocess.run([
    "keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12",
    "-keystore", str(keystore), "-storepass:env", "PULSAR_SIGNING_PASSWORD",
    "-keypass:env", "PULSAR_SIGNING_PASSWORD", "-alias", "pulsargram-x",
    "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
    "-dname", "CN=Pulsargram X",
  ], env=environment, check=True, capture_output=True)
  configuration.write_text(
    "keystore.file=" + str(keystore.resolve()) + "\n" +
    "keystore.password=" + password + "\n" +
    "key.alias=pulsargram-x\nkey.password=" + password + "\n"
  )
  configuration.chmod(0o600)
  keystore.chmod(0o600)
  return configuration


def signing_values(configuration):
  signing = PROPERTIES.read_properties(configuration)
  values = {
    "KEYSTORE_BASE64": base64.b64encode(Path(signing["keystore.file"]).read_bytes()).decode(),
    "KEYSTORE_PASSWORD": signing["keystore.password"],
    "KEY_ALIAS": signing["key.alias"],
    "KEY_PASSWORD": signing["key.password"],
  }
  forbidden = ROOT / ".personal/forbidden.txt"
  if forbidden.is_file():
    values["PUBLIC_FORBIDDEN_PATTERNS"] = forbidden.read_text()
  return values


def upload(configuration, firebase=None, api_properties=None):
  account = RepositorySecrets()
  values = signing_values(configuration)
  if firebase:
    values["FIREBASE_CONFIG_BASE64"] = firebase_value(firebase)
  if api_properties:
    api = PROPERTIES.read_properties(api_properties)
    values["TELEGRAM_API_ID"] = api["telegram.api_id"]
    values["TELEGRAM_API_HASH"] = api["telegram.api_hash"]
  for name, value in values.items():
    account.set(name, value)
  print(f"Configured {len(values)} public repository secrets without displaying their values")


def main(argv=None):
  os.umask(0o077)
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--upload", action="store_true",
                      help="Upload to the repository using its isolated GitHub login")
  parser.add_argument("--firebase", type=Path,
                      help="Public app's google-services.json, for --upload")
  parser.add_argument("--api-properties", type=Path,
                      help="Public app's Telegram API properties, for --upload")
  args = parser.parse_args(argv)
  configuration = prepare()
  if args.upload:
    upload(configuration, args.firebase, args.api_properties)
  else:
    print("Neutral public signing configuration prepared:")
    print(configuration)


if __name__ == "__main__":
  try:
    main()
  except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
    print(f"Public signing setup failed: {error}", file=sys.stderr)
    sys.exit(1)
