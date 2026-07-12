#!/usr/bin/env bash

set -Eeuo pipefail

# Exercise the final macOS archive exactly as a user receives it.

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <release-archive.zip> <expected-version>" >&2
  exit 2
fi

archive_input=$1
expected_version=$2
binary_name=ccc-usage-dashboard
startup_timeout=${SMOKE_STARTUP_TIMEOUT_SECONDS:-60}
shutdown_timeout=${SMOKE_SHUTDOWN_TIMEOUT_SECONDS:-15}

if [[ ! -f "$archive_input" ]]; then
  echo "Release archive does not exist: $archive_input" >&2
  exit 1
fi
if [[ -z "$expected_version" ]]; then
  echo "Expected version must not be empty." >&2
  exit 1
fi

archive_directory=$(cd "$(dirname "$archive_input")" && pwd)
archive_path="$archive_directory/$(basename "$archive_input")"
work_directory=$(mktemp -d "${TMPDIR:-/tmp}/ccc-packaged-smoke.XXXXXX")
extract_directory="$work_directory/extracted"
application_home="$work_directory/home"
smoke_log=${SMOKE_LOG_PATH:-"$work_directory/ccc-usage-dashboard.log"}
smoke_pid=

mkdir -p "$extract_directory" "$application_home" "$(dirname "$smoke_log")"
: > "$smoke_log"

stop_process() {
  if [[ -z "$smoke_pid" ]]; then
    return 0
  fi
  if ! kill -0 "$smoke_pid" 2>/dev/null; then
    wait "$smoke_pid" 2>/dev/null || true
    smoke_pid=
    return 0
  fi

  kill -TERM "$smoke_pid"
  for ((attempt = 0; attempt < shutdown_timeout * 4; attempt++)); do
    if ! kill -0 "$smoke_pid" 2>/dev/null; then
      wait "$smoke_pid" 2>/dev/null || true
      smoke_pid=
      return 0
    fi
    sleep 0.25
  done

  echo "The packaged process did not stop within ${shutdown_timeout} seconds; forcing termination." >&2
  kill -KILL "$smoke_pid" 2>/dev/null || true
  wait "$smoke_pid" 2>/dev/null || true
  smoke_pid=
  return 1
}

cleanup() {
  status=$?
  trap - EXIT INT TERM

  if [[ -n "$smoke_pid" ]]; then
    stop_process || status=1
  fi
  if [[ $status -ne 0 && -s "$smoke_log" ]]; then
    echo "----- packaged application log -----" >&2
    cat "$smoke_log" >&2
    echo "----- end packaged application log -----" >&2
  fi
  if [[ ${SMOKE_KEEP_WORK_DIRECTORY:-false} == true ]]; then
    echo "Packaged smoke-test directory retained at $work_directory" >&2
  else
    rm -rf "$work_directory"
  fi
  exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! command -v ditto >/dev/null 2>&1; then
  echo "ditto is required to extract the macOS release archive." >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to validate the health response." >&2
  exit 1
fi
if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "sqlite3 is required to validate the initialized database." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to allocate isolated test ports." >&2
  exit 1
fi

ditto -x -k "$archive_path" "$extract_directory"
smoke_binary="$extract_directory/$binary_name"
if [[ ! -x "$smoke_binary" ]]; then
  echo "The archive does not contain an executable $binary_name at its root." >&2
  exit 1
fi
if [[ ${SMOKE_VERIFY_CODESIGN:-false} == true ]]; then
  codesign --verify --deep --strict --verbose=2 "$smoke_binary"
fi

read -r http_port grpc_port < <(python3 - <<'PY'
import socket

sockets = []
ports = []
for _ in range(2):
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    sockets.append(sock)
    ports.append(sock.getsockname()[1])
print(*ports)
for sock in sockets:
    sock.close()
PY
)

(
  cd "$extract_directory"
  exec env \
    CCC_USAGE_DASHBOARD_HOME="$application_home" \
    CCC_USAGE_DASHBOARD_CODEX_ENABLED=false \
    CCC_USAGE_DASHBOARD_CLAUDE_ENABLED=false \
    QUARKUS_HTTP_PORT="$http_port" \
    QUARKUS_GRPC_SERVER_PORT="$grpc_port" \
    QUARKUS_LOG_FILE_ENABLED=false \
    "./$binary_name"
) > "$smoke_log" 2>&1 &
smoke_pid=$!

health_response=
for ((attempt = 1; attempt <= startup_timeout; attempt++)); do
  if ! kill -0 "$smoke_pid" 2>/dev/null; then
    wait "$smoke_pid" 2>/dev/null || true
    smoke_pid=
    echo "The packaged process exited before becoming healthy." >&2
    exit 1
  fi
  if health_response=$(curl --fail --silent --show-error \
      "http://127.0.0.1:${http_port}/health" 2>/dev/null); then
    break
  fi
  sleep 1
done

if [[ -z "$health_response" ]]; then
  echo "The packaged process did not become healthy within ${startup_timeout} seconds." >&2
  exit 1
fi

if ! jq -e \
    --arg expected_version "$expected_version" \
    '.status == "ok"
      and .service == "ccc-usage-dashboard"
      and .version == $expected_version' \
    <<< "$health_response" >/dev/null; then
  echo "Unexpected health response: $health_response" >&2
  exit 1
fi

summary_response=$(curl --fail --silent --show-error \
  "http://127.0.0.1:${http_port}/api/summary?source=codex&range=6h")
if ! jq -e \
    '.totalCredits == 0
      and .totalCostUsd == 0
      and .totalInputTokens == 0
      and .totalCachedInputTokens == 0
      and .totalOutputTokens == 0
      and .totalEvents == 0
      and .eventsWithCredits == 0
      and .eventsWithCost == 0
      and .rawRecords == 0
      and .annotateCursor == 0
      and .backlog == 0
      and (.usage | type == "array")' \
    <<< "$summary_response" >/dev/null; then
  echo "Unexpected summary response: $summary_response" >&2
  exit 1
fi

database="$application_home/data/ccc-usage-dashboard.sqlite"
if [[ ! -f "$database" ]]; then
  echo "The packaged process did not initialize the expected database: $database" >&2
  exit 1
fi
if [[ $(sqlite3 "$database" 'PRAGMA quick_check;') != ok ]]; then
  echo "The initialized SQLite database failed PRAGMA quick_check." >&2
  exit 1
fi

for table in otel_log_records annotated_events usage_samples cursor; do
  if [[ $(sqlite3 "$database" \
      "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$table';") != 1 ]]; then
    echo "The initialized database is missing table: $table" >&2
    exit 1
  fi
done

for column in source_tool request_id trigger originator host cost_usd reported_cost_usd; do
  if [[ $(sqlite3 "$database" \
      "SELECT count(*) FROM pragma_table_info('annotated_events') WHERE name = '$column';") != 1 ]]; then
    echo "The initialized annotated_events table is missing column: $column" >&2
    exit 1
  fi
done

if ! kill -0 "$smoke_pid" 2>/dev/null; then
  wait "$smoke_pid" 2>/dev/null || true
  smoke_pid=
  echo "The packaged process exited unexpectedly during validation." >&2
  exit 1
fi
if ! stop_process; then
  exit 1
fi

echo "Packaged artifact smoke test passed for ccc-usage-dashboard $expected_version."
