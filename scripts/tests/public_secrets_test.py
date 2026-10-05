#!/usr/bin/env python3
"""Check public credential isolation, identity rejection and hidden secret setup."""

import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import public_secrets as SECRETS

SPEC = importlib.util.spec_from_file_location("public_secret_setup",
                                              ROOT / "scripts/setup-public-secrets.py")
SETUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SETUP)
SIGNING_NAMES = {"KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD",
                 "PUBLIC_FORBIDDEN_PATTERNS"}


class AccountSecretsTest(unittest.TestCase):
  def setUp(self):
    self.temporary = tempfile.TemporaryDirectory()
    self.addCleanup(self.temporary.cleanup)
    self.root = Path(self.temporary.name)
    account = self.root / ".public-repo/.git/public-account"
    account.mkdir(parents=True)
    (account / "repository").write_text("PublicOwner/PublicClient\n")
    self.addCleanup(patch.stopall)
    patch.object(SECRETS, "ROOT", self.root).start()
    patch.dict(os.environ, {"PULSAR_REPO": str(self.root / ".public-repo")}).start()

  def test_wrong_account_is_rejected_before_any_secret_upload(self):
    with patch.object(SECRETS.RepositorySecrets, "run", return_value=b"OtherAccount\n") as run:
      with self.assertRaisesRegex(ValueError, "does not match"):
        SECRETS.RepositorySecrets()
    self.assertEqual(run.call_count, 1)
    self.assertEqual(run.call_args.args, ("api", "user", "--jq", ".login"))

  def test_upload_targets_public_repo_and_keeps_value_off_arguments_and_output(self):
    with patch.object(SECRETS.RepositorySecrets, "run", return_value=b"PublicOwner\n"):
      account = SECRETS.RepositorySecrets()
    completed = subprocess.CompletedProcess([], 0, stdout=b"", stderr=b"")
    with patch.object(SECRETS.subprocess, "run", return_value=completed) as run:
      with patch("sys.stdout", new_callable=io.StringIO) as output:
        account.set("TELEGRAM_BOT_TOKEN", "fixture-secret-value")
    self.assertNotIn("fixture-secret-value", " ".join(run.call_args.args[0]))
    self.assertEqual(run.call_args.kwargs["input"], b"fixture-secret-value")
    self.assertIn("PublicOwner/PublicClient", run.call_args.args[0])
    self.assertNotIn("fixture-secret-value", output.getvalue())

  def test_failed_cli_never_reports_credential_response(self):
    with patch.object(SECRETS.RepositorySecrets, "run", return_value=b"PublicOwner\n"):
      account = SECRETS.RepositorySecrets()
    completed = subprocess.CompletedProcess([], 1, stdout=b"", stderr=b"fixture-sensitive-error")
    with patch.object(SECRETS.subprocess, "run", return_value=completed):
      with self.assertRaises(ValueError) as raised:
        account.set("TELEGRAM_BOT_TOKEN", "fixture-secret-value")
    self.assertNotIn("fixture-sensitive-error", str(raised.exception))
    self.assertNotIn("fixture-secret-value", str(raised.exception))


class SecretInputTest(unittest.TestCase):
  def test_accepts_public_username_link_and_numeric_channel_id(self):
    for value in ("@PublicChannel", "https://t.me/PublicChannel", "PublicChannel"):
      self.assertEqual(SECRETS.channel_value(value), "@PublicChannel")
    self.assertEqual(SECRETS.channel_value("-1001234567890"), "-1001234567890")
    for value in ("1234567890", "https://example.com/channel", "https://t.me/+invite", ""):
      with self.assertRaises(ValueError):
        SECRETS.channel_value(value)

  def test_firebase_requires_public_package_and_rejects_private_identity(self):
    with tempfile.TemporaryDirectory() as temporary:
      root = Path(temporary)
      (root / ".personal").mkdir()
      (root / ".personal/forbidden.txt").write_text("private-fixture\n")
      path = root / "google-services.json"
      config = {"project_info": {"project_id": "public-client"}, "client": [{
        "client_info": {"android_client_info": {"package_name": "com.pulsargram.x"}},
      }]}
      path.write_text(json.dumps(config))
      with patch.object(SECRETS, "ROOT", root):
        self.assertEqual(json.loads(base64.b64decode(SECRETS.firebase_value(path))), config)
        config["project_info"]["project_id"] = "private-fixture"
        path.write_text(json.dumps(config))
        with self.assertRaisesRegex(ValueError, "private identity"):
          SECRETS.firebase_value(path)
        config["project_info"]["project_id"] = "public-client"
        config["client"][0]["client_info"]["android_client_info"]["package_name"] = "com.other.app"
        path.write_text(json.dumps(config))
        with self.assertRaisesRegex(ValueError, "com.pulsargram.x"):
          SECRETS.firebase_value(path)
        for invalid in ([], {"client": [{}]}, {"client": [{"client_info": []}]}):
          path.write_text(json.dumps(invalid))
          with self.assertRaises(ValueError):
            SECRETS.firebase_value(path)

  def test_existing_secrets_are_preserved_and_missing_bot_uses_hidden_prompt(self):
    account = Mock(repository="PublicOwner/PublicClient")
    existing = SIGNING_NAMES | set(SETUP.PROMPTS) - {"TELEGRAM_BOT_TOKEN"}
    account.names.side_effect = [existing, existing | {"TELEGRAM_BOT_TOKEN"}]
    token = "12345:" + "x" * 30
    with patch.object(SETUP.getpass, "getpass", return_value=token) as hidden:
      with patch("builtins.input") as visible:
        with patch("sys.stdout", new_callable=io.StringIO) as output:
          SETUP.configure(account)
    hidden.assert_called_once()
    visible.assert_not_called()
    account.set.assert_called_once_with("TELEGRAM_BOT_TOKEN", token)
    self.assertNotIn(token, output.getvalue())
    self.assertIn("All public secrets configured", output.getvalue())

  def test_blank_missing_values_are_skipped_and_reported(self):
    account = Mock(repository="PublicOwner/PublicClient")
    account.names.return_value = SIGNING_NAMES
    with patch.object(SETUP.getpass, "getpass", return_value=""):
      with patch("builtins.input", return_value=""):
        with patch("sys.stdout", new_callable=io.StringIO) as output:
          SETUP.configure(account)
    account.set.assert_not_called()
    self.assertIn("Still needed:", output.getvalue())
    self.assertIn("TELEGRAM_BOT_TOKEN", output.getvalue())

  def test_nonterminal_invocation_cannot_echo_credentials(self):
    with patch.object(SETUP.sys.stdin, "isatty", return_value=False):
      with patch.object(SETUP, "RepositorySecrets") as account:
        with self.assertRaisesRegex(ValueError, "terminal"):
          SETUP.main([])
    account.assert_not_called()


if __name__ == "__main__":
  unittest.main()
