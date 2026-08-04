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
graalvm_version=$(sed -n 's/^graalvmVersion=//p' gradle.properties)
GRAALVM_VERSION=${GRAALVM_VERSION:-${graalvm_version}-graalce}

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

# The SDK is a library, so compile its native test image; executable projects build their main image.
native_tasks=(
  :control-plane:nativeCompile
  :sdks:java:nativeTestCompile
  :services:java:warm-echo:nativeCompile
  :nanofaas-cli:nativeCompile
  :functions:java:word-stats:nativeCompile
  :functions:java:json-transform:nativeCompile
  :functions:java:roman-numeral:nativeCompile
  :functions:java:figlet:nativeCompile
  :functions:java:word-stats-lite:nativeCompile
  :functions:java:json-transform-lite:nativeCompile
  :functions:java:roman-numeral-lite:nativeCompile
)
./gradlew "${native_tasks[@]}" -PcontrolPlaneModules=all

RUN_SMOKE=${RUN_SMOKE:-1}
if [ "$RUN_SMOKE" = "1" ]; then
  CONTROL_BIN="platform/control-plane/build/native/nativeCompile/control-plane"
  WARM_ECHO_BIN="services/java/warm-echo/build/native/nativeCompile/warm-echo"
  CONTROL_PID=""
  WARM_ECHO_PID=""

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
  WARM_ECHO_PORT=$(pick_available_port "${WARM_ECHO_SERVER_PORT:-18090}")
  if [ "$WARM_ECHO_PORT" = "$CONTROL_PORT" ] || [ "$WARM_ECHO_PORT" = "$MGMT_PORT" ]; then
    WARM_ECHO_PORT=$(pick_available_port "$((WARM_ECHO_PORT + 1))")
  fi

  trap 'if [ -n "${CONTROL_PID}" ] && kill -0 "${CONTROL_PID}" 2>/dev/null; then kill "${CONTROL_PID}"; fi; if [ -n "${WARM_ECHO_PID}" ] && kill -0 "${WARM_ECHO_PID}" 2>/dev/null; then kill "${WARM_ECHO_PID}"; fi' EXIT

  echo "Native smoke ports: control=${CONTROL_PORT}, management=${MGMT_PORT}, warm-echo=${WARM_ECHO_PORT}"

  if [ -x "$CONTROL_BIN" ]; then
    "$CONTROL_BIN" --server.port="${CONTROL_PORT}" --management.server.port="${MGMT_PORT}" &
    CONTROL_PID=$!
  else
    echo "Missing control-plane native binary: $CONTROL_BIN" >&2
    exit 1
  fi

  if [ -x "$WARM_ECHO_BIN" ]; then
    "$WARM_ECHO_BIN" --server.port="${WARM_ECHO_PORT}" &
    WARM_ECHO_PID=$!
  else
    echo "Missing warm-echo native binary: $WARM_ECHO_BIN" >&2
    exit 1
  fi

  wait_for_http_ok "http://localhost:${MGMT_PORT}/actuator/health"
  wait_for_http_ok "http://localhost:${WARM_ECHO_PORT}/actuator/health"
  curl -sf -X POST "http://localhost:${WARM_ECHO_PORT}/invoke" \
    -H 'Content-Type: application/json' \
    -H 'X-Execution-Id: native-smoke' \
    -d '{"input":{"message":"hi"}}' > /dev/null

  ./gradlew :nanofaas-cli:nativeSmoke

  echo "Native smoke checks OK"
fi
