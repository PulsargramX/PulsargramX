#!/usr/bin/env bash
# Use hidden prompts and the repository-local public GitHub login.
set -euo pipefail
source_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$source_root/scripts/setup-public-secrets.py" "$@"
