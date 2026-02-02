#!/usr/bin/env bash
set -euo pipefail

export SDKMAN_NON_INTERACTIVE=true
export SDKMAN_DISABLE_PROMPT=true
export PAGER=${PAGER:-cat}

# Allow alternative container runtimes by providing a Docker-compatible socket.
if [ -n "${CONTAINER_HOST:-}" ]; then
  export DOCKER_HOST="$CONTAINER_HOST"
fi
if [ -n "${CONTAINER_TLS_VERIFY:-}" ]; then
  export DOCKER_TLS_VERIFY="$CONTAINER_TLS_VERIFY"
fi
if [ -n "${CONTAINER_CERT_PATH:-}" ]; then
  export DOCKER_CERT_PATH="$CONTAINER_CERT_PATH"
fi

if [ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
  echo "SDKMAN not found. Install SDKMAN first: https://sdkman.io/" >&2
  exit 1
fi

set +u
export ZSH_VERSION=${ZSH_VERSION:-}
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -u

set +u
JDK_VERSION=${JDK_VERSION:-}
if [ -z "$JDK_VERSION" ]; then
  JDK_VERSION=$(sdk list java | awk '/21.*-tem/ {print $NF; exit}')
fi
if [ -z "$JDK_VERSION" ]; then
  JDK_VERSION=$(sdk list java | awk '/21.*-amzn/ {print $NF; exit}')
fi
if [ -z "$JDK_VERSION" ]; then
  JDK_VERSION=$(sdk list java | awk '/21.*-/ {print $NF; exit}')
fi

if [ -z "$JDK_VERSION" ]; then
  echo "Unable to determine a Java 21 JDK version from SDKMAN." >&2
  echo "Set JDK_VERSION (e.g., 21.0.2-tem) and re-run." >&2
  exit 1
fi

if [ ! -d "$HOME/.sdkman/candidates/java/$JDK_VERSION" ]; then
  sdk install java "$JDK_VERSION"
fi
sdk use java "$JDK_VERSION"
set -u

./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.BuildpackE2eTest
