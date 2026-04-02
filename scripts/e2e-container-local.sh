#!/usr/bin/env bash
set -euo pipefail

#
# No-k8s managed DEPLOYMENT E2E.
#
# Builds the control-plane with the `container-deployment-provider` module,
# starts it locally with `nanofaas.deployment.default-backend=container-local`,
# registers a DEPLOYMENT function without endpointUrl, verifies the provider-backed
# response metadata, exercises sync invoke + replica scale-up, then deletes the
# function and checks container cleanup.
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

API_PORT=${CONTROL_PLANE_PORT:-18080}
MGMT_PORT=${CONTROL_PLANE_MGMT_PORT:-18081}
BASE_URL="http://127.0.0.1:${API_PORT}"
MGMT_URL="http://127.0.0.1:${MGMT_PORT}"

FUNCTION_NAME=${FUNCTION_NAME:-container-local-e2e}
FUNCTION_SLUG=${FUNCTION_NAME//[^[:alnum:]-]/-}
CONTROL_PLANE_MODULES=${CONTROL_PLANE_MODULES:-container-deployment-provider}
RUNTIME_ADAPTER=${RUNTIME_ADAPTER:-${CONTAINER_RUNTIME:-}}
FUNCTION_IMAGE=${FUNCTION_IMAGE:-nanofaas/function-runtime:e2e-container-local}
SKIP_FUNCTION_IMAGE_BUILD=${SKIP_FUNCTION_IMAGE_BUILD:-false}
CONTROL_PLANE_JAR="${PROJECT_ROOT}/control-plane/build/libs/app.jar"
FUNCTION_RUNTIME_BUILD_DIR="${PROJECT_ROOT}/function-runtime"
LOG_FILE="$(mktemp -t nanofaas-container-local-e2e.XXXXXX.log)"
TMP_DIR="$(mktemp -d -t nanofaas-container-local-e2e.XXXXXX)"

CP_PID=""
FUNCTION_DELETED=false

info() {
  printf '[e2e-container-local] %s\n' "$1"
}

fail() {
  printf '[e2e-container-local] FAIL: %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

select_runtime_adapter() {
  local candidate

  if [[ -n "${RUNTIME_ADAPTER}" ]]; then
    echo "${RUNTIME_ADAPTER}"
    return 0
  fi

  for candidate in docker podman nerdctl; do
    if command -v "${candidate}" >/dev/null 2>&1 && "${candidate}" version >/dev/null 2>&1; then
      echo "${candidate}"
      return 0
    fi
  done

  for candidate in docker podman nerdctl; do
    if command -v "${candidate}" >/dev/null 2>&1; then
      echo "${candidate}"
      return 0
    fi
  done

  return 1
}

json_get_file() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)

for part in sys.argv[2].split("."):
    if isinstance(value, list):
        value = value[int(part)]
    else:
        value = value[part]

if value is None:
    raise SystemExit(1)

if isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
PY
}

assert_json_field() {
  local file=$1
  local field=$2
  local expected=$3
  local actual
  actual=$(json_get_file "${file}" "${field}")
  if [[ "${actual}" != "${expected}" ]]; then
    fail "Expected ${field}=${expected}, got ${actual}"
  fi
}

managed_container_names() {
  "${RUNTIME_ADAPTER}" ps -a \
    --filter "name=nanofaas-${FUNCTION_SLUG}-r" \
    --format '{{.Names}}' 2>/dev/null || true
}

managed_container_count() {
  local names
  names=$(managed_container_names | sed '/^[[:space:]]*$/d' || true)
  if [[ -z "${names}" ]]; then
    echo 0
  else
    printf '%s\n' "${names}" | wc -l | tr -d ' '
  fi
}

wait_for_http_ok() {
  local url=$1
  local max_attempts=${2:-60}
  local attempt
  for attempt in $(seq 1 "${max_attempts}"); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_container_count() {
  local expected=$1
  local max_attempts=${2:-60}
  local attempt current
  for attempt in $(seq 1 "${max_attempts}"); do
    current=$(managed_container_count)
    if [[ "${current}" == "${expected}" ]]; then
      return 0
    fi
    sleep 1
  done
  fail "Timed out waiting for ${expected} managed containers"
}

cleanup() {
  local exit_code=$?

  if [[ "${FUNCTION_DELETED}" != "true" ]]; then
    curl -fsS -X DELETE "${BASE_URL}/v1/functions/${FUNCTION_NAME}" >/dev/null 2>&1 || true
  fi

  if [[ -n "${CP_PID}" ]]; then
    kill "${CP_PID}" 2>/dev/null || true
    wait "${CP_PID}" 2>/dev/null || true
  fi

  "${RUNTIME_ADAPTER}" rm -f \
    "nanofaas-${FUNCTION_SLUG}-r1" \
    "nanofaas-${FUNCTION_SLUG}-r2" \
    "nanofaas-${FUNCTION_SLUG}-r3" >/dev/null 2>&1 || true

  rm -rf "${TMP_DIR}"

  if [[ ${exit_code} -ne 0 ]]; then
    info "Control-plane log tail:"
    tail -n 80 "${LOG_FILE}" >&2 || true
  fi
  rm -f "${LOG_FILE}"

  exit "${exit_code}"
}
trap cleanup EXIT

require_cmd java
require_cmd python3
require_cmd curl
RUNTIME_ADAPTER=$(select_runtime_adapter) || fail "No Docker-compatible runtime found on PATH (docker, podman, nerdctl)"
require_cmd "${RUNTIME_ADAPTER}"

if ! "${RUNTIME_ADAPTER}" version >/dev/null 2>&1; then
  fail "Container runtime adapter '${RUNTIME_ADAPTER}' is installed but not operational"
fi

info "Building control-plane and function-runtime artifacts"
(cd "${PROJECT_ROOT}" && ./gradlew \
  :control-plane:bootJar \
  :function-runtime:bootJar \
  -PcontrolPlaneModules="${CONTROL_PLANE_MODULES}" \
  --quiet)

if [[ "${SKIP_FUNCTION_IMAGE_BUILD}" == "true" ]]; then
  info "Skipping function-runtime image build; using existing image ${FUNCTION_IMAGE}"
else
  info "Building function-runtime image ${FUNCTION_IMAGE}"
  (cd "${PROJECT_ROOT}" && "${RUNTIME_ADAPTER}" build -t "${FUNCTION_IMAGE}" "${FUNCTION_RUNTIME_BUILD_DIR}" >/dev/null)
fi

info "Starting control-plane with container-local provider"
java -jar "${CONTROL_PLANE_JAR}" \
  --server.port="${API_PORT}" \
  --management.server.port="${MGMT_PORT}" \
  --sync-queue.enabled=false \
  --nanofaas.deployment.default-backend=container-local \
  --nanofaas.container-local.runtime-adapter="${RUNTIME_ADAPTER}" \
  --nanofaas.container-local.bind-host=127.0.0.1 \
  >"${LOG_FILE}" 2>&1 &
CP_PID=$!

info "Waiting for control-plane readiness"
wait_for_http_ok "${MGMT_URL}/actuator/health" 90 || fail "Control-plane did not become healthy"

REGISTER_RESPONSE="${TMP_DIR}/register.json"
cat > "${TMP_DIR}/register-request.json" <<EOF
{"name":"${FUNCTION_NAME}","image":"${FUNCTION_IMAGE}","timeoutMs":5000,"concurrency":2,"queueSize":20,"maxRetries":3,"executionMode":"DEPLOYMENT"}
EOF

info "Registering provider-backed DEPLOYMENT function"
curl -fsS \
  -H 'Content-Type: application/json' \
  -d @"${TMP_DIR}/register-request.json" \
  "${BASE_URL}/v1/functions" \
  > "${REGISTER_RESPONSE}"

assert_json_field "${REGISTER_RESPONSE}" "name" "${FUNCTION_NAME}"
assert_json_field "${REGISTER_RESPONSE}" "requestedExecutionMode" "DEPLOYMENT"
assert_json_field "${REGISTER_RESPONSE}" "effectiveExecutionMode" "DEPLOYMENT"
assert_json_field "${REGISTER_RESPONSE}" "deploymentBackend" "container-local"

ENDPOINT_URL=$(json_get_file "${REGISTER_RESPONSE}" "endpointUrl")
if [[ -z "${ENDPOINT_URL}" ]]; then
  fail "endpointUrl must be present for provider-backed registration"
fi

info "Waiting for managed container and stable proxy endpoint"
wait_for_container_count 1 60
wait_for_http_ok "${ENDPOINT_URL%/invoke}/health" 60 || fail "Stable proxy endpoint did not become healthy"

INVOKE_RESPONSE="${TMP_DIR}/invoke.json"
curl -fsS \
  -H 'Content-Type: application/json' \
  -d '{"input":{"message":"hello-container-local"}}' \
  "${BASE_URL}/v1/functions/${FUNCTION_NAME}:invoke" \
  > "${INVOKE_RESPONSE}"
assert_json_field "${INVOKE_RESPONSE}" "status" "success"
assert_json_field "${INVOKE_RESPONSE}" "output.message" "hello-container-local"

info "Scaling managed function to 2 replicas"
SCALE_RESPONSE="${TMP_DIR}/scale.json"
curl -fsS \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"replicas":2}' \
  "${BASE_URL}/v1/functions/${FUNCTION_NAME}/replicas" \
  > "${SCALE_RESPONSE}"
assert_json_field "${SCALE_RESPONSE}" "name" "${FUNCTION_NAME}"
assert_json_field "${SCALE_RESPONSE}" "replicas" "2"
wait_for_container_count 2 60

SCALED_INVOKE_RESPONSE="${TMP_DIR}/invoke-scaled.json"
curl -fsS \
  -H 'Content-Type: application/json' \
  -d '{"input":{"message":"scaled-container-local"}}' \
  "${BASE_URL}/v1/functions/${FUNCTION_NAME}:invoke" \
  > "${SCALED_INVOKE_RESPONSE}"
assert_json_field "${SCALED_INVOKE_RESPONSE}" "status" "success"
assert_json_field "${SCALED_INVOKE_RESPONSE}" "output.message" "scaled-container-local"

info "Deleting managed function and verifying cleanup"
curl -fsS -X DELETE "${BASE_URL}/v1/functions/${FUNCTION_NAME}" >/dev/null
FUNCTION_DELETED=true
wait_for_container_count 0 60

GET_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/v1/functions/${FUNCTION_NAME}")
if [[ "${GET_STATUS}" != "404" ]]; then
  fail "Expected GET /v1/functions/${FUNCTION_NAME} to return 404 after delete, got ${GET_STATUS}"
fi

info "PASS: container-local managed DEPLOYMENT flow"
