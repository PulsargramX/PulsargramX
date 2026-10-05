#!/usr/bin/env bash
# GitHub CLI login and settings live only in this project's public repository.
set -euo pipefail
source_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
default_repository="$source_root/.public-repo"
if [ -d "$source_root/.git/public-account" ]; then
  default_repository="$source_root"
fi
repository="${PULSAR_REPO:-$default_repository}"
if [ ! -d "$repository/.git/public-account" ]; then
  echo "Prepare the public repository with setup-public-account.py first." >&2
  exit 1
fi
repository="$(cd "$repository" && pwd)"
if [ "${1:-}" = auth ] && [ "${2:-}" = setup-git ]; then
  echo "This project uses repository-local SSH; global credential setup is unnecessary." >&2
  exit 1
fi
account="$repository/.git/public-account"
mkdir -p "$account/gh"
chmod 700 "$account/gh"
cd "$repository"
client=gh
if [ -x "$account/bin/gh" ]; then
  client="$account/bin/gh"
fi
# Keep authentication in this repository's protected config, outside the system keyring.
if [ "${1:-}" = auth ] && [ "${2:-}" = login ]; then
  set -- "$@" --insecure-storage
  echo "Open the displayed device-login link manually using this repository's GitHub account." >&2
fi
# On Linux, gh can read or clear keyring entries even with --insecure-storage.
# Deny its session-bus connection; browser login uses the displayed link manually.
exec env -u GH_TOKEN -u GITHUB_TOKEN -u GH_ENTERPRISE_TOKEN -u GITHUB_ENTERPRISE_TOKEN \
  -u GH_HOST GH_CONFIG_DIR="$account/gh" XDG_CACHE_HOME="$account/cache" \
  DBUS_SESSION_BUS_ADDRESS=unix:path=/dev/null GH_BROWSER=/bin/true "$client" "$@"
