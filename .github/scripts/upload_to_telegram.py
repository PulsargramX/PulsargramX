#!/usr/bin/env python3
"""
Upload APK files to Telegram channel using Telethon as a bot
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import subprocess
import sys

DEFAULT_CAPTION_LENGTH = 1024
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SUCCESS_MARKER = "refs/tags/telegram-last-success"
sys.path.insert(0, str(REPOSITORY_ROOT / "scripts"))
from public_secrets import channel_value

REQUIRED_SECRETS = ("TELEGRAM_API_ID", "TELEGRAM_API_HASH", "TELEGRAM_BOT_TOKEN",
                    "TELEGRAM_CHANNEL_ID")


class ConfigurationError(ValueError):
    pass


def upload_configuration(environment):
    missing = [name for name in REQUIRED_SECRETS if not environment.get(name, "").strip()]
    if missing:
        raise ConfigurationError("Missing repository secrets: " + ", ".join(missing))
    api_id = environment["TELEGRAM_API_ID"].strip()
    api_hash = environment["TELEGRAM_API_HASH"].strip()
    bot_token = environment["TELEGRAM_BOT_TOKEN"].strip()
    if not re.fullmatch(r"[1-9][0-9]*", api_id):
        raise ConfigurationError("TELEGRAM_API_ID must be a positive integer")
    if not re.fullmatch(r"[A-Fa-f0-9]{32}", api_hash):
        raise ConfigurationError("TELEGRAM_API_HASH must contain 32 hexadecimal characters")
    if not re.fullmatch(r"[0-9]+:[A-Za-z0-9_-]{20,}", bot_token):
        raise ConfigurationError("TELEGRAM_BOT_TOKEN has an invalid format")
    try:
        channel = channel_value(environment["TELEGRAM_CHANNEL_ID"])
    except ValueError as error:
        raise ConfigurationError("TELEGRAM_CHANNEL_ID: " + str(error)) from None
    return int(api_id), api_hash, bot_token, int(channel) if channel.startswith("-100") else channel


def commit_subject(message):
    """Return only the subject line of a commit message."""
    return message.splitlines()[0].strip() if message else ""


def subjects_from_event():
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if not event_path:
        return []

    try:
        with open(event_path, encoding="utf-8") as event_file:
            event = json.load(event_file)
    except (OSError, ValueError):
        return []

    before = event.get("before", "")
    after = event.get("after", "")
    if before and after and set(before) != {"0"}:
        try:
            output = subprocess.check_output(
                ["git", "log", "--reverse", "-z", "--format=%s", f"{before}..{after}"],
                cwd=REPOSITORY_ROOT,
                text=True,
            )
            subjects = [subject.strip() for subject in output.split("\0") if subject.strip()]
            if subjects:
                return subjects
        except subprocess.CalledProcessError:
            pass

    subjects = [commit_subject(commit.get("message", "")) for commit in event.get("commits", [])]
    return [subject for subject in subjects if subject]


def subjects_since_last_success(commit):
    """Return commit subjects since the last successful Telegram upload."""
    try:
        subprocess.check_output(
            ["git", "rev-parse", "--verify", "--quiet", SUCCESS_MARKER],
            cwd=REPOSITORY_ROOT,
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except subprocess.CalledProcessError:
        return None

    is_ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", SUCCESS_MARKER, commit],
        cwd=REPOSITORY_ROOT,
        check=False,
    )
    if is_ancestor.returncode != 0:
        return None

    try:
        output = subprocess.check_output(
            ["git", "log", "--reverse", "-z", "--format=%s", f"{SUCCESS_MARKER}..{commit}"],
            cwd=REPOSITORY_ROOT,
            text=True,
        )
    except subprocess.CalledProcessError:
        return None

    return [subject.strip() for subject in output.split("\0") if subject.strip()]


def get_commit_subjects():
    commit = os.environ.get("GITHUB_SHA", "HEAD")
    subjects = subjects_since_last_success(commit)
    if subjects is not None:
        return subjects

    subjects = subjects_from_event()
    if subjects:
        return subjects

    try:
        subject = subprocess.check_output(
            ["git", "log", "-1", "--format=%s", commit],
            cwd=REPOSITORY_ROOT,
            text=True,
        ).strip()
        return [subject] if subject else []
    except subprocess.CalledProcessError:
        return []


def get_version():
    properties = {}
    with open(REPOSITORY_ROOT / "version.properties", encoding="utf-8") as version_file:
        for line in version_file:
            key, separator, value = line.strip().partition("=")
            if separator:
                properties[key] = value
    return f"{properties['version.major']}.{properties['version.app']}"


def build_caption(version, subjects, max_length):
    caption = f"Pulsargram X v{version}\n\nChanges:"
    more = "\nAnd more!"

    for index, subject in enumerate(subjects):
        line = f"\n- {subject}"
        has_more = index < len(subjects) - 1
        reserved = more if has_more else ""
        if len((caption + line + reserved).encode("utf-16-le")) // 2 > max_length:
            if len((caption + more).encode("utf-16-le")) // 2 <= max_length:
                caption += more
            return caption
        caption += line

    return caption


async def upload_file(api_id, api_hash, bot_token, channel_id, file_path, version, subjects):
    """Upload file to Telegram channel using bot"""
    from telethon import TelegramClient, functions
    from telethon.tl.types import DocumentAttributeFilename

    # Keep authentication in memory; session credentials never become artifacts.
    client = TelegramClient(None, api_id, api_hash)

    try:
        # Start as bot
        await client.start(bot_token=bot_token)

        config = await client(functions.help.GetConfigRequest())
        max_caption_length = config.caption_length_max or DEFAULT_CAPTION_LENGTH
        caption = build_caption(version, subjects, max_caption_length)

        print(f"Uploading {file_path}...")
        print(f"File size: {os.path.getsize(file_path) / (1024*1024):.2f} MB")
        print(f"Caption length: {len(caption.encode('utf-16-le')) // 2}/{max_caption_length}")

        # Resolve the target before starting a potentially large upload.
        channel = await client.get_input_entity(channel_id)
        await client.send_file(
            channel,
            file_path,
            caption=caption,
            parse_mode=None,
            attributes=[DocumentAttributeFilename(os.path.basename(file_path))],
            force_document=True
        )

        print(f"✅ Successfully uploaded {os.path.basename(file_path)}")

    finally:
        await client.disconnect()

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path, help="Verified public APK; credentials come from environment")
    args = parser.parse_args(argv)
    api_id, api_hash, bot_token, channel_id = upload_configuration(os.environ)
    if not args.file.is_file() or args.file.suffix.lower() != ".apk":
        raise ConfigurationError("The verified public APK file does not exist")
    asyncio.run(upload_file(
        api_id,
        api_hash,
        bot_token,
        channel_id,
        str(args.file),
        get_version(),
        get_commit_subjects(),
    ))

if __name__ == '__main__':
    try:
        main()
    except ConfigurationError as error:
        # Validation messages contain field names only, never credential values.
        print(f"Telegram upload failed: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # Network errors may embed a token or authentication request in their text.
        print(f"Telegram upload failed ({type(error).__name__}); check bot permissions and secrets.",
              file=sys.stderr)
        sys.exit(1)
