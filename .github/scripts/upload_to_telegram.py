#!/usr/bin/env python3
"""
Upload public APKs with automatically exported client changes using Telethon as a bot.
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
from public_changelog import message_changes, summarize_paths

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


def publication_baseline(commit):
    """Prefer the last successful upload; use the push baseline on first publication."""
    candidates = [SUCCESS_MARKER]
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if event_path:
        try:
            with open(event_path, encoding="utf-8") as event_file:
                before = json.load(event_file).get("before", "")
            if isinstance(before, str) and re.fullmatch(r"[0-9a-fA-F]{40,64}", before):
                candidates.append(before)
        except (OSError, ValueError, AttributeError):
            pass
    for candidate in candidates:
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", candidate, commit],
            cwd=REPOSITORY_ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if ancestor.returncode == 0:
            return candidate
    return None


def get_release_changes():
    commit = os.environ.get("GITHUB_SHA", "HEAD")
    baseline = publication_baseline(commit)
    arguments = ["git", "log", "--reverse", "--format=%H"]
    arguments += [f"{baseline}..{commit}"] if baseline else ["-1", commit]
    commits = subprocess.check_output(arguments, cwd=REPOSITORY_ROOT, text=True).splitlines()
    changes = []
    for revision in commits:
        message = subprocess.check_output(
            ["git", "show", "-s", "--format=%B", revision], cwd=REPOSITORY_ROOT, text=True,
        )
        entries = message_changes(message)
        if entries is None:
            # Old generic snapshots have no descriptions. Summarize their public files,
            # never their commit titles or bodies, which can contain development details.
            paths = subprocess.check_output(
                ["git", "diff-tree", "--root", "--no-commit-id", "-r", "--name-only",
                 "--no-renames", "-z", revision], cwd=REPOSITORY_ROOT, text=True,
            ).split("\0")
            entries = summarize_paths(paths)
        for entry in entries:
            if entry not in changes:
                changes.append(entry)
    return changes


def get_version():
    properties = {}
    with open(REPOSITORY_ROOT / "version.properties", encoding="utf-8") as version_file:
        for line in version_file:
            key, separator, value = line.strip().partition("=")
            if separator:
                properties[key] = value
    return f"{properties['version.major']}.{properties['version.app']}"


def build_caption(version, changes, max_length):
    caption = f"🔨 New Build\nPulsargram X v{version}\n\nChanges:"
    if not changes:
        return caption + "\nNo new client changes."
    more = "\nAnd more!"

    for index, change in enumerate(changes):
        line = f"\n- {change}"
        has_more = index < len(changes) - 1
        reserved = more if has_more else ""
        if len((caption + line + reserved).encode("utf-16-le")) // 2 > max_length:
            if len((caption + more).encode("utf-16-le")) // 2 <= max_length:
                caption += more
            return caption
        caption += line

    return caption


async def upload_file(api_id, api_hash, bot_token, channel_id, file_path, version, changes):
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
        caption = build_caption(version, changes, max_caption_length)

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
        get_release_changes(),
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
