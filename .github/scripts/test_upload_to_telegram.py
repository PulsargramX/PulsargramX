#!/usr/bin/env python3

import importlib.util
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


class CommitSubjectsTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.repository = Path(self.temp_dir.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.com")

    def tearDown(self):
        self.temp_dir.cleanup()

    def git(self, *arguments):
        return subprocess.check_output(
            ["git", *arguments],
            cwd=self.repository,
            text=True,
        ).strip()

    def commit(self, subject):
        tracked_file = self.repository / "history.txt"
        with tracked_file.open("a", encoding="utf-8") as history:
            history.write(f"{subject}\n")
        self.git("add", "history.txt")
        self.git("commit", "--message", subject)
        return self.git("rev-parse", "HEAD")

    def test_uses_all_commits_since_success_marker(self):
        self.commit("Published build")
        self.git("tag", "telegram-last-success")
        self.commit("First unpublished change")
        current_commit = self.commit("Fix failed build")

        with mock.patch.object(UPLOAD, "REPOSITORY_ROOT", self.repository):
            subjects = UPLOAD.subjects_since_last_success(current_commit)

        self.assertEqual(
            subjects,
            ["First unpublished change", "Fix failed build"],
        )

    def test_empty_range_does_not_fall_back_to_push_event(self):
        current_commit = self.commit("Published build")
        self.git("tag", "telegram-last-success")

        with (
            mock.patch.object(UPLOAD, "REPOSITORY_ROOT", self.repository),
            mock.patch.dict(os.environ, {"GITHUB_SHA": current_commit}),
            mock.patch.object(UPLOAD, "subjects_from_event") as event_subjects,
        ):
            subjects = UPLOAD.get_commit_subjects()

        self.assertEqual(subjects, [])
        event_subjects.assert_not_called()

    def test_falls_back_to_push_event_without_marker(self):
        current_commit = self.commit("Current change")

        with (
            mock.patch.object(UPLOAD, "REPOSITORY_ROOT", self.repository),
            mock.patch.dict(os.environ, {"GITHUB_SHA": current_commit}),
            mock.patch.object(
                UPLOAD,
                "subjects_from_event",
                return_value=["Current change"],
            ),
        ):
            subjects = UPLOAD.get_commit_subjects()

        self.assertEqual(subjects, ["Current change"])


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

    def test_caption_fits_telegram_limit_with_astral_emoji(self):
        subjects = ["Feature " + "🚀" * 10] * 50
        caption = UPLOAD.build_caption("1.2", subjects, 1024)
        self.assertLessEqual(len(caption.encode("utf-16-le")) // 2, 1024)
        self.assertIn("And more!", caption)
        self.assertTrue(caption.startswith("Pulsargram X"))


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
