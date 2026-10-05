#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  native)
    task=:app:externalNativeBuildPublicLatestArm64Release
    heap=2g
    ;;
  apk)
    task=:app:assemblePublicLatestArm64Release
    heap=4g
    ;;
  *)
    echo "Usage: $0 native|apk" >&2
    exit 64
    ;;
esac

export PATH="/usr/lib/ccache:$PATH"
CMAKE_C_COMPILER_LAUNCHER="$(command -v ccache)"
export CMAKE_C_COMPILER_LAUNCHER
export CMAKE_CXX_COMPILER_LAUNCHER="$CMAKE_C_COMPILER_LAUNCHER"
export CCACHE_MAXSIZE=512M

resource_snapshot() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
  free -m
  df -h .
  # Process names only: command lines can contain credentials.
  ps -eo pid,ppid,comm,rss,pcpu --sort=-rss | sed -n '1,12p'
  if [ -r /proc/pressure/memory ]; then
    cat /proc/pressure/memory
  fi
  # AGP buffers native build output; expose its latest progress while it runs.
  if [ -d app/build/intermediates/cxx ]; then
    find app/build/intermediates/cxx -name 'build_stdout*.txt' -type f \
      -exec tail -n 2 {} +
  fi
}

monitor_resources() {
  sleep_pid=
  trap 'exit 0' TERM INT
  trap 'if [ -n "$sleep_pid" ]; then kill "$sleep_pid" 2>/dev/null || true; fi' EXIT
  while true; do
    resource_snapshot || true
    sleep 30 &
    sleep_pid=$!
    wait "$sleep_pid"
  done
}

monitor_resources &
monitor_pid=$!
finish() {
  result=$?
  trap - EXIT
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  resource_snapshot || true
  if [ "$result" -ne 0 ]; then
    # These remain available if the build fails without killing the runner.
    sudo -n dmesg --ctime 2>/dev/null |
      grep -Ei 'out of memory|oom-kill|killed process' | tail -n 20 || true
    if [ -d app/build/intermediates/cxx ]; then
      find app/build/intermediates/cxx -name 'build_stderr*.txt' -type f \
        -exec tail -n 40 {} + || true
    fi
  fi
  ccache -s || true
  exit "$result"
}
trap finish EXIT

echo "Building $task with a $heap heap and two native jobs"
# Keep Kotlin inside the bounded Gradle JVM. Separate invocations prevent the
# native linker from sharing memory with application compilation and R8.
./gradlew "$task" --no-daemon --no-parallel --max-workers=1 --console=plain --stacktrace \
  "-Dorg.gradle.jvmargs=-Xmx$heap -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8" \
  -Pkotlin.compiler.execution.strategy=in-process -Ptgx.nativeJobs=2 -Pclient.edition=public
