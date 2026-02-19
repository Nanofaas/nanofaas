#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

export SDKMAN_NON_INTERACTIVE=true
export PAGER=${PAGER:-cat}

if [ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
  echo "SDKMAN not found. Install SDKMAN first: https://sdkman.io/" >&2
  exit 1
fi

# SDKMAN init expects ZSH_VERSION in some environments; avoid nounset issues.
set +u
export ZSH_VERSION=${ZSH_VERSION:-}
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -u

# SDKMAN scripts are not nounset-safe; disable temporarily.
set +u
GRAALVM_VERSION=${GRAALVM_VERSION:-}
if [ -z "$GRAALVM_VERSION" ]; then
  GRAALVM_VERSION=$(sdk list java | awk '/-graal/ {print $NF; exit}')
fi

if [ -z "$GRAALVM_VERSION" ]; then
  echo "Unable to determine a GraalVM version from SDKMAN." >&2
  echo "Set GRAALVM_VERSION (e.g., 17.0.11-graal) and re-run." >&2
  exit 1
fi

INSTALLED=false
if [ -d "$HOME/.sdkman/candidates/java/$GRAALVM_VERSION" ]; then
  INSTALLED=true
else
  if sdk list java | grep -F "$GRAALVM_VERSION" | grep -q "installed"; then
    INSTALLED=true
  fi
fi

if [ "$INSTALLED" != "true" ]; then
  sdk install java "$GRAALVM_VERSION"
fi

sdk use java "$GRAALVM_VERSION"
set -u

./gradlew :control-plane:nativeCompile
./gradlew :function-runtime:nativeCompile

RUN_SMOKE=${RUN_SMOKE:-1}
if [ "$RUN_SMOKE" = "1" ]; then
  CONTROL_BIN="control-plane/build/native/nativeCompile/control-plane"
  RUNTIME_BIN="function-runtime/build/native/nativeCompile/function-runtime"
  CONTROL_PID=""
  RUNTIME_PID=""

  is_port_in_use() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
      lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
      return
    fi
    if command -v ss >/dev/null 2>&1; then
      ss -ltn "sport = :${port}" | tail -n +2 | grep -q .
      return
    fi
    return 1
  }

  pick_available_port() {
    local port="$1"
    while is_port_in_use "$port"; do
      port=$((port + 1))
    done
    echo "$port"
  }

  wait_for_http_ok() {
    local url="$1"
    local attempts="${2:-30}"
    local sleep_secs="${3:-1}"

    local i=1
    while [ "$i" -le "$attempts" ]; do
      if curl -sf "$url" >/dev/null; then
        return 0
      fi
      sleep "$sleep_secs"
      i=$((i + 1))
    done
    return 1
  }

  CONTROL_PORT=$(pick_available_port "${CONTROL_SERVER_PORT:-18080}")
  MGMT_PORT=$(pick_available_port "${CONTROL_MANAGEMENT_PORT:-18081}")
  if [ "$MGMT_PORT" = "$CONTROL_PORT" ]; then
    MGMT_PORT=$(pick_available_port "$((MGMT_PORT + 1))")
  fi
  RUNTIME_PORT=$(pick_available_port "${RUNTIME_SERVER_PORT:-18090}")
  if [ "$RUNTIME_PORT" = "$CONTROL_PORT" ] || [ "$RUNTIME_PORT" = "$MGMT_PORT" ]; then
    RUNTIME_PORT=$(pick_available_port "$((RUNTIME_PORT + 1))")
  fi

  trap 'if [ -n "${CONTROL_PID}" ] && kill -0 "${CONTROL_PID}" 2>/dev/null; then kill "${CONTROL_PID}"; fi; if [ -n "${RUNTIME_PID}" ] && kill -0 "${RUNTIME_PID}" 2>/dev/null; then kill "${RUNTIME_PID}"; fi' EXIT

  echo "Native smoke ports: control=${CONTROL_PORT}, management=${MGMT_PORT}, runtime=${RUNTIME_PORT}"

  if [ -x "$CONTROL_BIN" ]; then
    "$CONTROL_BIN" --server.port="${CONTROL_PORT}" --management.server.port="${MGMT_PORT}" &
    CONTROL_PID=$!
  else
    echo "Missing control-plane native binary: $CONTROL_BIN" >&2
    exit 1
  fi

  if [ -x "$RUNTIME_BIN" ]; then
    "$RUNTIME_BIN" --server.port="${RUNTIME_PORT}" &
    RUNTIME_PID=$!
  else
    echo "Missing function-runtime native binary: $RUNTIME_BIN" >&2
    exit 1
  fi

  wait_for_http_ok "http://localhost:${MGMT_PORT}/actuator/health"
  wait_for_http_ok "http://localhost:${RUNTIME_PORT}/actuator/health"
  curl -sf -X POST "http://localhost:${RUNTIME_PORT}/invoke" \
    -H 'Content-Type: application/json' \
    -d '{"input":{"message":"hi"}}' > /dev/null

  echo "Native smoke checks OK"
fi
