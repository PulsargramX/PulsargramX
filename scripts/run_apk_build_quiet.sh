#!/usr/bin/env bash
# Build a verified CI-style APK; successful stdout contains only its path.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$script_dir/build-apk.py" "$@"
