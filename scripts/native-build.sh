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

  if [ -x "$CONTROL_BIN" ]; then
    "$CONTROL_BIN" --server.port=18080 --management.server.port=18081 &
    CONTROL_PID=$!
  fi

  if [ -x "$RUNTIME_BIN" ]; then
    "$RUNTIME_BIN" --server.port=18090 &
    RUNTIME_PID=$!
  fi

  trap '[[ -n "${CONTROL_PID:-}" ]] && kill "$CONTROL_PID"; [[ -n "${RUNTIME_PID:-}" ]] && kill "$RUNTIME_PID"' EXIT

  sleep 3
  curl -sf http://localhost:18081/actuator/health > /dev/null
  curl -sf -X POST http://localhost:18090/invoke \
    -H 'Content-Type: application/json' \
    -d '{"input":{"message":"hi"}}' > /dev/null

  echo "Native smoke checks OK"
fi
