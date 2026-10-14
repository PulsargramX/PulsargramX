"""Select public client changes without exporting private development metadata."""

import re


HEADING = "Public client changes:"
CLIENT_PREFIXES = ("app/src/main/", "app/src/public/", "app/jni/", "app/thirdparty/",
                   "tdlib/", "tgcalls/", "vkryl/", "extension/")


def client_path(name):
  if not (name.startswith(CLIENT_PREFIXES) or name in ("tdlib", "tgcalls")):
    return False
  parts = name.lower().split("/")
  if any(part in ("test", "tests", "scripts", "docs", "doc", ".github") for part in parts):
    return False
  return not (name.endswith((".md", ".gradle", ".gradle.kts", ".properties", ".cmake")) or
              parts[-1] in ("cmakelists.txt", "makefile"))


def safe_subject(subject, patterns=()):
  """A mixed or sensitive commit gets a summary of its public files instead."""
  lower = subject.lower()
  if (not subject or len(subject) > 160 or
      any(pattern.strip().lower() in lower for pattern in patterns if pattern.strip()) or
      re.search(r"personal|private|secret|protected|self.destruct|bypass|advert|\bads\b|"
                r"https?://|[\w.+-]+@[\w.-]+|\b[0-9a-f]{7,64}\b|[\r\n\x00-\x1f]", lower)):
    return None
  return subject


def summarize_paths(paths):
  """Describe affected features when a safe public commit title is unavailable."""
  changes = []
  for path in sorted(paths):
    if not client_path(path):
      continue
    name = path.lower()
    if "forum" in name or "topic" in name:
      change = "Improve forum topics and message previews"
    elif any(word in name for word in ("keyboard", "rootframelayout", "input", "ime/")):
      change = "Improve keyboard and input panel behavior"
    elif "sticker" in name or "emoji" in name:
      change = "Improve stickers and emoji"
    elif "poll" in name:
      change = "Improve polls"
    elif any(word in name for word in ("camera", "media", "video", "photo", "gallery")):
      change = "Improve media viewing and sharing"
    elif "notification" in name:
      change = "Improve notifications"
    elif "call" in name:
      change = "Improve voice and video calls"
    elif "chat" in name or "message" in name:
      change = "Improve chats and messaging"
    elif any(word in name for word in ("settings", "theme", "lang", "/res/")):
      change = "Improve interface and settings"
    else:
      change = "Improve client reliability and compatibility"
    if change not in changes:
      changes.append(change)
  return changes


def message_changes(message):
  """Only the exporter-owned section is a changelog; other message text is ignored."""
  lines = message.splitlines()
  if HEADING not in lines:
    return None
  changes = []
  for line in lines[lines.index(HEADING) + 1:]:
    if not line.startswith("- "):
      break
    change = line[2:].strip()
    if change and change not in changes:
      changes.append(change)
  return changes
