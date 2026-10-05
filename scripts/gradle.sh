#!/usr/bin/env bash
# Run Gradle without streaming its verbose output to the caller. On failure, print
# the failed task and the portion of the log that contains the actionable error.

set -uo pipefail

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 <Gradle task or option> [...]" >&2
  exit 64
fi

repo_root="${TGX_GRADLE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$repo_root"

if [ -n "${TGX_GRADLE_LOG_FILE:-}" ]; then
  log_file="$TGX_GRADLE_LOG_FILE"
  remove_log=false
else
  log_file="$(mktemp "${TMPDIR:-/tmp}/client-gradle.XXXXXX.log")"
  remove_log=true
fi

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  # Also stop daemons after failures and interruptions. Bound cleanup so a
  # broken daemon cannot leave this script waiting indefinitely.
  timeout --kill-after=2s 10s ./gradlew --stop > /dev/null 2>&1 || true
  if "$remove_log"; then
    rm -f "$log_file"
  fi
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

gradle_args=("$@")
has_no_daemon=false
has_console=false
for arg in "${gradle_args[@]}"; do
  case "$arg" in
    --no-daemon) has_no_daemon=true ;;
    --console|--console=*) has_console=true ;;
  esac
done

if ! "$has_no_daemon"; then
  gradle_args+=(--no-daemon)
fi
if ! "$has_console"; then
  gradle_args+=(--console=plain)
fi

start_time=$SECONDS
./gradlew "${gradle_args[@]}" >"$log_file" 2>&1
gradle_status=$?
elapsed=$((SECONDS - start_time))

if [ "$gradle_status" -eq 0 ]; then
  requested_tasks=()
  for arg in "${gradle_args[@]}"; do
    [[ "$arg" != -* ]] && requested_tasks+=("$arg")
  done
  if [ "${#requested_tasks[@]}" -eq 0 ]; then
    requested_tasks=("Gradle command")
  fi
  printf 'Gradle succeeded in %ss: %s\n' "$elapsed" "${requested_tasks[*]}"
  exit 0
fi

failed_tasks="$(sed -n 's/^> Task \(.*\) FAILED$/\1/p' "$log_file" | paste -sd ', ' -)"
printf 'Gradle failed (exit %s) after %ss' "$gradle_status" "$elapsed" >&2
if [ -n "$failed_tasks" ]; then
  printf ' at task(s): %s' "$failed_tasks" >&2
fi
printf '\n\n' >&2

# Javac diagnostics precede the FAILED task marker. Include those and Gradle's
# explanation after FAILURE; the intervening task banner alone hides the cause.
awk '
  /error:|^e: |fatal error:|CMake Error|Execution failed for task|Caused by:/ {
    diagnostics[++count] = $0
  }
  /^FAILURE: Build failed/ { show = 1 }
  show { explanation = explanation $0 ORS }
  show && /^\* Try:/ { show = 0 }
  END {
    start = count > 30 ? count - 29 : 1
    for (i = start; i <= count; i++) print diagnostics[i]
    print explanation
  }
' "$log_file" | tail -n 100 >&2

exit "$gradle_status"
