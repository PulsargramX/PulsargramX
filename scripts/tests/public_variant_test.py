#!/usr/bin/env python3
"""Check public restrictions, clean snapshot history and repository-local account isolation."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]


def module(name, filename):
  spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
  result = importlib.util.module_from_spec(spec)
  spec.loader.exec_module(result)
  return result


AUDIT = module("public_audit_test", "verify-public.py")
EXPORT = module("public_export_test", "export-public.py")
ACCOUNT = module("public_account_test", "setup-public-account.py")


def git(root, *args):
  return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE).decode()


class PublicSnapshotTest(unittest.TestCase):
  def setUp(self):
    self.temporary = tempfile.TemporaryDirectory()
    self.addCleanup(self.temporary.cleanup)
    self.root = Path(self.temporary.name) / "source"
    self.root.mkdir()
    git(self.root, "init", "-b", "main")
    git(self.root, "config", "user.name", "Private author")
    git(self.root, "config", "user.email", "private@example.invalid")
    git(self.root, "config", "commit.gpgsign", "false")
    self.policy = self.root / "app/src/public/java/org/thunderdog/challegram/config/ClientPolicy.java"
    self.policy.parent.mkdir(parents=True)
    self.policy.write_text("public class ClientPolicy {}\n")
    (self.root / "README.md").write_text("Public app\n")
    (self.root / ".personal").mkdir()
    (self.root / ".personal/private.txt").write_text("private identity\n")
    git(self.root, "add", ".")
    git(self.root, "commit", "-m", "Private development history")
    self.destination = Path(self.temporary.name) / "public"

  def test_snapshot_has_its_own_history_and_keeps_private_files_out(self):
    with patch.dict(os.environ, {"GIT_AUTHOR_NAME": "Private ambient author",
                                "GIT_AUTHOR_EMAIL": "private-ambient@example.invalid",
                                "GIT_COMMITTER_NAME": "Private ambient committer",
                                "GIT_COMMITTER_EMAIL": "private-ambient@example.invalid"}):
      EXPORT.snapshot(self.root, self.destination, "HEAD", ("private identity",))
    self.assertEqual(git(self.destination, "rev-list", "--count", "HEAD").strip(), "1")
    history = git(self.destination, "log", "--format=%an %ae %cn %ce %s")
    self.assertNotIn("Private author", history)
    self.assertNotIn("Private ambient", history)
    self.assertNotIn("Private development history", history)
    self.assertFalse((self.destination / ".personal").exists())
    self.assertEqual((self.destination / "README.md").read_text(), "Public app\n")
    (self.root / "README.md").write_text("One shared bug fix\n")
    git(self.root, "commit", "-am", "Shared fix")
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    self.assertEqual((self.destination / "README.md").read_text(), "One shared bug fix\n")
    self.assertEqual(git(self.destination, "rev-list", "--count", "HEAD").strip(), "2")

  def test_identity_in_source_is_rejected_before_destination_creation(self):
    (self.root / "README.md").write_text("private identity\n")
    git(self.root, "commit", "-am", "Private name")
    with self.assertRaisesRegex(ValueError, "identity text"):
      EXPORT.snapshot(self.root, self.destination, "HEAD", ("private identity",))
    self.assertFalse(self.destination.exists())

  def test_uninitialized_submodules_keep_the_export_clean_and_repeatable(self):
    dependency = git(self.root, "rev-parse", "HEAD").strip()
    git(self.root, "update-index", "--add", "--cacheinfo",
        "160000," + dependency + ",native/dependency")
    git(self.root, "commit", "-m", "Track upstream dependency")
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    self.assertTrue((self.destination / "native/dependency").is_dir())
    self.assertEqual(git(self.destination, "status", "--porcelain"), "")
    self.assertIn(dependency, git(self.destination, "ls-tree", "HEAD", "native/dependency"))
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    self.assertEqual(git(self.destination, "rev-list", "--count", "HEAD").strip(), "1")

  def test_dirty_destination_is_preserved(self):
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    (self.destination / "README.md").write_text("User edit\n")
    with self.assertRaisesRegex(ValueError, "destination edits"):
      EXPORT.snapshot(self.root, self.destination, "HEAD")
    self.assertEqual((self.destination / "README.md").read_text(), "User edit\n")

  def test_apk_scan_catches_utf8_and_android_utf16_identity(self):
    for data in ("private identity".encode(), "private identity".encode("utf-16-le")):
      with self.assertRaisesRegex(ValueError, "identity text"):
        AUDIT.scan(data, ("private identity",), "artifact")

  def test_source_audit_rejects_private_identity_in_commit_metadata(self):
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    git(self.destination, "-c", "user.name=private identity", "commit", "--allow-empty",
        "-m", "Public update")
    with self.assertRaisesRegex(ValueError, "public commit authors"):
      AUDIT.check_source(self.destination, ("private identity",))

  @unittest.skipUnless(shutil.which("ssh-keygen"), "OpenSSH is unavailable")
  def test_secondary_account_uses_only_repository_local_identity_and_key(self):
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    key = ACCOUNT.prepare(self.destination, "public-test", "public-test@users.noreply.github.com",
                          fetch_host_keys=False, repository_name="PublicClient")
    self.assertTrue(key.is_file())
    config = (key.parent / "ssh_config").read_text()
    self.assertIn("IdentityAgent none", config)
    self.assertIn("IdentitiesOnly yes", config)
    self.assertIn(str(key.parent), config)
    self.assertNotIn("~/.ssh", config)
    self.assertEqual(git(self.destination, "config", "--local", "user.email").strip(),
                     "public-test@users.noreply.github.com")
    self.assertEqual(git(self.root, "config", "--local", "user.email").strip(),
                     "private@example.invalid")
    self.assertEqual(git(self.destination, "remote", "get-url", "origin").strip(),
                     "git@github.com:public-test/PublicClient.git")
    self.assertEqual((key.parent / "repository").read_text(), "public-test/PublicClient\n")

  def test_noreply_email_uses_the_public_account_id(self):
    with patch.object(ACCOUNT.urllib.request, "urlopen") as open_request:
      response = open_request.return_value.__enter__.return_value
      response.read.return_value = b'{"login": "PublicAccount", "id": 123456}'
      self.assertEqual(ACCOUNT.noreply_email("PublicAccount"),
                       "123456+PublicAccount@users.noreply.github.com")
      self.assertEqual(open_request.call_args.args[0].full_url,
                       "https://api.github.com/users/PublicAccount")

  @unittest.skipUnless(shutil.which("ssh-keygen"), "OpenSSH is unavailable")
  def test_cli_keeps_authentication_in_the_repository_and_ignores_ambient_tokens(self):
    EXPORT.snapshot(self.root, self.destination, "HEAD")
    key = ACCOUNT.prepare(self.destination, "public-test", "public-test@users.noreply.github.com",
                          fetch_host_keys=False)
    executable = key.parent / "bin/gh"
    executable.parent.mkdir()
    executable.write_text('''#!/usr/bin/env python3
import json, os, sys
print(json.dumps({"config": os.environ.get("GH_CONFIG_DIR"), "arguments": sys.argv[1:],
                  "keyring_bus": os.environ.get("DBUS_SESSION_BUS_ADDRESS"),
                  "browser": os.environ.get("GH_BROWSER"), "host": os.environ.get("GH_HOST"),
                  "cache": os.environ.get("XDG_CACHE_HOME"),
                  "ambient_tokens": any(os.environ.get(key) for key in
                    ("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN"))}))
''')
    executable.chmod(0o700)
    environment = dict(os.environ, PULSAR_REPO=str(self.destination), GH_TOKEN="fixture-token",
                       GH_HOST="fixture.example", DBUS_SESSION_BUS_ADDRESS="fixture:session")
    result = subprocess.check_output([str(ROOT / "scripts/public-gh.sh"), "auth", "login", "--web"],
                                     env=environment)
    actual = json.loads(result)
    self.assertEqual(actual["config"], str(key.parent / "gh"))
    self.assertIn("--insecure-storage", actual["arguments"])
    self.assertFalse(actual["ambient_tokens"])
    self.assertEqual(actual["keyring_bus"], "unix:path=/dev/null")
    self.assertEqual(actual["browser"], "/bin/true")
    self.assertIsNone(actual["host"])
    self.assertEqual(actual["cache"], str(key.parent / "cache"))


class ClientPolicyTest(unittest.TestCase):
  @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java is unavailable")
  def test_public_policy_enforces_server_permissions_and_ignores_personal_preferences(self):
    self.compile_and_run("app/src/public", False)

  @unittest.skipUnless(shutil.which("javac") and shutil.which("java") and
                       (ROOT / ".personal/java").is_dir(), "Local personal overlay is unavailable")
  def test_personal_policy_retains_local_preferences(self):
    self.compile_and_run(".personal", True)

  def compile_and_run(self, directory, personal):
    with tempfile.TemporaryDirectory() as temporary:
      work = Path(temporary)
      tdapi = work / "org/drinkless/tdlib/TdApi.java"
      tdapi.parent.mkdir(parents=True)
      tdapi.write_text('''package org.drinkless.tdlib;
public class TdApi {
  public static class MessageProperties { public boolean canBeForwarded; }
  public static class MessageSelfDestructType { public int getConstructor() { return 1; } }
  public static class MessageSelfDestructTypeImmediately extends MessageSelfDestructType {
    public static final int CONSTRUCTOR = 2;
    public int getConstructor() { return CONSTRUCTOR; }
  }
}''')
      settings = work / "org/thunderdog/challegram/unsorted/Settings.java"
      settings.parent.mkdir(parents=True)
      settings.write_text('''package org.thunderdog.challegram.unsorted;
import java.util.HashMap;
public class Settings {
  public static final Settings INSTANCE = new Settings();
  public final HashMap<String, Boolean> values = new HashMap<>();
  public static Settings instance() { return INSTANCE; }
  public boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
}''')
      runner = work / "Check.java"
      runner.write_text('''import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.config.ClientPolicy;
import org.thunderdog.challegram.unsorted.Settings;
class Check {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    boolean personal = Boolean.parseBoolean(args[0]);
    check(ClientPolicy.allowContentAccess(false) == personal);
    check(ClientPolicy.allowContentAccess(true));
    check(ClientPolicy.enforceProtection() != personal);
    check(ClientPolicy.useDisposableViewer() != personal);
    check(ClientPolicy.showAds() != personal);
    check(ClientPolicy.canSaveMessage(false, true) == personal);
    check(ClientPolicy.shouldOpenContent(true) != personal);
    TdApi.MessageProperties permission = new TdApi.MessageProperties();
    check(ClientPolicy.sendAsCopy(false, new TdApi.MessageProperties[]{permission}) == personal);
    check(ClientPolicy.sendAsCopy(true, null));
    check(ClientPolicy.shouldOpenMedia(new TdApi.MessageSelfDestructTypeImmediately(), true) != personal);
    Settings.instance().values.put("view_without_timer", false);
    Settings.instance().values.put("allow_save_secret_media", false);
    Settings.instance().values.put("show_ads", true);
    check(ClientPolicy.useContentTimer());
    check(ClientPolicy.shouldOpenContent(true));
    check(ClientPolicy.showAds());
    check(!ClientPolicy.canSaveMessage(false, true));
    check(ClientPolicy.shouldOpenMedia(new TdApi.MessageSelfDestructType(), false) == personal);
  }
}''')
      policy = ROOT / directory / "java/org/thunderdog/challegram/config/ClientPolicy.java"
      subprocess.run(["javac", "-d", str(work), str(tdapi), str(settings), str(policy), str(runner)],
                     check=True, capture_output=True)
      subprocess.run(["java", "-cp", str(work), "Check", str(personal).lower()],
                     check=True, capture_output=True)


if __name__ == "__main__":
  unittest.main()
