#!/usr/bin/env python3

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
from types import SimpleNamespace


SCRIPT_PATH = Path(__file__).with_name("upload_to_telegram.py")
SPEC = importlib.util.spec_from_file_location("upload_to_telegram", SCRIPT_PATH)
UPLOAD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UPLOAD)


class ReleaseChangesTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.repository = Path(self.temp_dir.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "commit.gpgsign", "false")
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(UPLOAD, "REPOSITORY_ROOT", self.repository).start()
        mock.patch.dict(os.environ, {"GITHUB_SHA": "HEAD", "GITHUB_EVENT_PATH": ""}).start()

    def git(self, *arguments):
        return subprocess.check_output(
            ["git", *arguments], cwd=self.repository, text=True,
        ).strip()

    def commit(self, subject, changes=None, path=None):
        if path:
            tracked = self.repository / path
            tracked.parent.mkdir(parents=True, exist_ok=True)
            with tracked.open("a") as history:
                history.write("change\n")
            self.git("add", path)
        message = subject
        if changes is not None:
            message += "\n\nPublic client changes:\n" + "".join("- " + c + "\n" for c in changes)
        self.git("commit", "--allow-empty", "--message", message)
        return self.git("rev-parse", "HEAD")

    def test_uses_all_exported_changes_since_success_and_deduplicates(self):
        self.commit("Published snapshot", ["Earlier public fix"])
        self.git("tag", "telegram-last-success")
        self.commit("Update shared client source", ["Fix keyboard animation"])
        self.commit("Update shared client source", [])
        self.commit("Update shared client source", ["Fix keyboard animation", "Improve topic previews"])
        self.assertEqual(UPLOAD.get_release_changes(),
                         ["Fix keyboard animation", "Improve topic previews"])

    def test_republishing_same_commit_has_no_changes(self):
        self.commit("Published snapshot", ["Earlier public fix"])
        self.git("tag", "telegram-last-success")
        self.assertEqual(UPLOAD.get_release_changes(), [])

    def test_build_only_snapshots_do_not_add_changes(self):
        self.commit("Published snapshot")
        self.git("tag", "telegram-last-success")
        self.commit("Fix CI configuration", [], ".github/workflows/build.yml")
        self.assertEqual(UPLOAD.get_release_changes(), [])

    def test_legacy_snapshots_are_summarized_without_subjects_or_private_files(self):
        self.commit("Published snapshot")
        self.git("tag", "telegram-last-success")
        self.commit("Update shared client source", path="app/src/main/java/widget/RootFrameLayout.java")
        self.commit("Personal customization", path=".personal/java/ClientPolicy.java")
        self.commit("Private build detail", path="scripts/build.py")
        self.assertEqual(UPLOAD.get_release_changes(), ["Improve keyboard and input panel behavior"])

    def test_first_push_uses_event_baseline_without_success_marker(self):
        before = self.commit("Published snapshot", ["Earlier public fix"])
        self.commit("Update shared client source", ["Improve topic previews"])
        event = self.repository / "event.json"
        event.write_text(json.dumps({"before": before,
                                    "commits": [{"message": "Personal-only customization"}]}))
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_PATH": str(event)}):
            self.assertEqual(UPLOAD.get_release_changes(), ["Improve topic previews"])

    def test_no_baseline_uses_current_snapshot(self):
        self.commit("Old snapshot", ["Earlier public fix"])
        self.commit("Update shared client source", ["Improve topic previews"])
        self.assertEqual(UPLOAD.get_release_changes(), ["Improve topic previews"])

    def test_success_marker_takes_priority_over_push_baseline(self):
        self.commit("Published snapshot", ["Earlier public fix"])
        self.git("tag", "telegram-last-success")
        before = self.commit("Failed publication", ["Fix keyboard animation"])
        self.commit("Retry publication", [])
        event = self.repository / "event.json"
        event.write_text(json.dumps({"before": before}))
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_PATH": str(event)}):
            self.assertEqual(UPLOAD.get_release_changes(), ["Fix keyboard animation"])

    def test_ignores_message_body_outside_exported_section(self):
        self.commit("Published snapshot")
        self.git("tag", "telegram-last-success")
        self.commit("Private title\n\nPrivate implementation details\n- Private feature\n",
                    ["Improve topic previews"])
        self.assertEqual(UPLOAD.get_release_changes(), ["Improve topic previews"])


class UploadConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.environment = {
            "TELEGRAM_API_ID": "12345",
            "TELEGRAM_API_HASH": "a" * 32,
            "TELEGRAM_BOT_TOKEN": "67890:" + "b" * 30,
            "TELEGRAM_CHANNEL_ID": "https://t.me/PublicChannel",
        }

    def test_environment_supports_username_and_numeric_targets(self):
        configured = UPLOAD.upload_configuration(self.environment)
        self.assertEqual(configured[0], 12345)
        self.assertEqual(configured[-1], "@PublicChannel")
        self.environment["TELEGRAM_CHANNEL_ID"] = "-1001234567890"
        self.assertEqual(UPLOAD.upload_configuration(self.environment)[-1], -1001234567890)

    def test_missing_and_malformed_secrets_fail_without_exposing_values(self):
        for name in UPLOAD.REQUIRED_SECRETS:
            environment = dict(self.environment)
            environment[name] = "fixture-invalid-secret"
            with self.assertRaises(UPLOAD.ConfigurationError) as raised:
                UPLOAD.upload_configuration(environment)
            self.assertNotIn("fixture-invalid-secret", str(raised.exception))
        with self.assertRaisesRegex(UPLOAD.ConfigurationError, "Missing repository secrets"):
            UPLOAD.upload_configuration({})

    def test_caption_shows_public_changes_and_handles_republication(self):
        self.assertEqual(
            UPLOAD.build_caption("0.1817", ["Fix keyboard animation"], 1024),
            "🔨 New Build\nPulsargram X v0.1817\n\nChanges:\n- Fix keyboard animation",
        )
        self.assertIn("No new client changes.", UPLOAD.build_caption("0.1817", [], 1024))

    def test_caption_fits_telegram_limit_with_astral_emoji(self):
        subjects = ["Feature " + "🚀" * 10] * 50
        caption = UPLOAD.build_caption("1.2", subjects, 1024)
        self.assertLessEqual(len(caption.encode("utf-16-le")) // 2, 1024)
        self.assertIn("And more!", caption)
        self.assertTrue(caption.startswith("🔨 New Build\nPulsargram X"))


class UploadLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_upload_uses_memory_session_resolved_channel_and_disconnects(self):
        client = mock.Mock()
        client.start = mock.AsyncMock()
        client.get_input_entity = mock.AsyncMock(return_value="resolved-channel")
        client.send_file = mock.AsyncMock()
        client.disconnect = mock.AsyncMock()
        client.__call__ = mock.AsyncMock(return_value=SimpleNamespace(caption_length_max=1024))
        # Special methods must be implemented on the class, not an individual mock attribute.
        class FakeClient:
            def __getattr__(self, name):
                return getattr(client, name)

            async def __call__(self, request):
                return await client.__call__(request)

        factory = mock.Mock(return_value=FakeClient())
        telethon = SimpleNamespace(TelegramClient=factory,
            functions=SimpleNamespace(help=SimpleNamespace(GetConfigRequest=mock.Mock())))
        types = SimpleNamespace(DocumentAttributeFilename=mock.Mock())
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "Pulsargram-X-test.apk"
            apk.write_bytes(b"fixture-apk")
            with mock.patch.dict("sys.modules", {"telethon": telethon, "telethon.tl.types": types}):
                with mock.patch("sys.stdout"):
                    await UPLOAD.upload_file(12345, "hash", "token", "@PublicChannel",
                                             str(apk), "1.2", [])
        self.assertIsNone(factory.call_args.args[0])
        client.get_input_entity.assert_awaited_once_with("@PublicChannel")
        self.assertEqual(client.send_file.call_args.args[0], "resolved-channel")
        client.disconnect.assert_awaited_once()

    async def test_failed_upload_still_disconnects(self):
        client = mock.Mock()
        client.start = mock.AsyncMock(side_effect=RuntimeError("fixture-authentication-error"))
        client.disconnect = mock.AsyncMock()
        telethon = SimpleNamespace(TelegramClient=mock.Mock(return_value=client), functions=mock.Mock())
        types = SimpleNamespace(DocumentAttributeFilename=mock.Mock())
        with mock.patch.dict("sys.modules", {"telethon": telethon, "telethon.tl.types": types}):
            with self.assertRaises(RuntimeError):
                await UPLOAD.upload_file(12345, "hash", "token", "@PublicChannel", "test.apk", "1.2", [])
        client.disconnect.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
