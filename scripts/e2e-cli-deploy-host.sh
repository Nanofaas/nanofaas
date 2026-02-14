#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "deploy-host-e2e"
CLI_BIN="${PROJECT_ROOT}/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas"

REGISTRY_PORT=${REGISTRY_PORT:-5050}
CONTROL_PLANE_PORT=${CONTROL_PLANE_PORT:-18080}
REGISTRY_CONTAINER_NAME=${REGISTRY_CONTAINER_NAME:-nanofaas-deploy-e2e-registry-$(date +%s)}
IMAGE_REPOSITORY=${IMAGE_REPOSITORY:-nanofaas/deploy-e2e}
FUNCTION_NAME=${FUNCTION_NAME:-deploy-e2e}
TAG=${TAG:-e2e-$(date +%s)}
BUILDX_BUILDER=${BUILDX_BUILDER:-}

WORK_DIR=""
REQUEST_BODY_PATH=""
FAKE_CP_SCRIPT_PATH=""
FAKE_CP_LOG_PATH=""
FAKE_CP_PID=""

cleanup() {
    local exit_code=$?
    e2e_cleanup_host_resources "${FAKE_CP_PID}" "${REGISTRY_CONTAINER_NAME}" "${WORK_DIR}"
    exit "${exit_code}"
}
trap cleanup EXIT

wait_http() {
    local url=$1
    local retries=${2:-30}
    local delay_seconds=${3:-1}
    local i
    for i in $(seq 1 "${retries}"); do
        if curl -fsS "${url}" >/dev/null 2>&1; then
            return 0
        fi
        sleep "${delay_seconds}"
    done
    return 1
}

wait_http_any_status() {
    local url=$1
    local retries=${2:-30}
    local delay_seconds=${3:-1}
    local i
    for i in $(seq 1 "${retries}"); do
        if curl -sS "${url}" >/dev/null 2>&1; then
            return 0
        fi
        sleep "${delay_seconds}"
    done
    return 1
}

check_prerequisites() {
    command -v docker >/dev/null 2>&1 || { err "docker not found"; exit 1; }
    command -v python3 >/dev/null 2>&1 || { err "python3 not found"; exit 1; }
    command -v curl >/dev/null 2>&1 || { err "curl not found"; exit 1; }
}

resolve_buildx_builder() {
    if [[ -z "${BUILDX_BUILDER}" ]]; then
        local current_context
        current_context=$(docker context show 2>/dev/null || true)
        if [[ -n "${current_context}" ]] && docker buildx inspect "${current_context}" >/dev/null 2>&1; then
            BUILDX_BUILDER="${current_context}"
        else
            BUILDX_BUILDER="default"
        fi
    fi

    if ! docker buildx inspect "${BUILDX_BUILDER}" >/dev/null 2>&1; then
        err "docker buildx builder '${BUILDX_BUILDER}' not found"
        exit 1
    fi

    export BUILDX_BUILDER
    log "Using buildx builder: ${BUILDX_BUILDER}"
}

build_cli() {
    log "Building nanofaas CLI on host..."
    (cd "${PROJECT_ROOT}" && ./gradlew :nanofaas-cli:installDist --no-daemon -q)
    if [[ ! -x "${CLI_BIN}" ]]; then
        err "CLI binary not found at ${CLI_BIN}"
        exit 1
    fi
}

setup_workdir() {
    WORK_DIR="$(mktemp -d -t nanofaas-deploy-host-e2e.XXXXXX)"
    REQUEST_BODY_PATH="${WORK_DIR}/function-request.json"
    FAKE_CP_SCRIPT_PATH="${WORK_DIR}/fake-control-plane.py"
    FAKE_CP_LOG_PATH="${WORK_DIR}/fake-control-plane.log"
    mkdir -p "${WORK_DIR}/context"
}

start_registry() {
    log "Starting local registry container (${REGISTRY_CONTAINER_NAME}) on port ${REGISTRY_PORT}..."
    docker rm -f "${REGISTRY_CONTAINER_NAME}" >/dev/null 2>&1 || true
    docker run -d --name "${REGISTRY_CONTAINER_NAME}" -p "${REGISTRY_PORT}:5000" registry:2 >/dev/null

    if ! wait_http "http://127.0.0.1:${REGISTRY_PORT}/v2/" 40 1; then
        err "Registry did not become ready on port ${REGISTRY_PORT}"
        exit 1
    fi
}

start_fake_control_plane() {
    log "Starting fake control-plane on port ${CONTROL_PLANE_PORT}..."
    cat > "${FAKE_CP_SCRIPT_PATH}" <<'PY'
import http.server
import json
import pathlib
import sys

port = int(sys.argv[1])
request_body_path = pathlib.Path(sys.argv[2])

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        if self.path != "/v1/functions":
            self.send_response(404)
            self.end_headers()
            return

        request_body_path.write_text(body, encoding="utf-8")

        try:
            payload = json.loads(body)
            name = payload.get("name", "unknown")
            image = payload.get("image", "")
        except Exception:
            name = "unknown"
            image = ""

        response = json.dumps({"name": name, "image": image}).encode("utf-8")
        self.send_response(201)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY

    python3 "${FAKE_CP_SCRIPT_PATH}" "${CONTROL_PLANE_PORT}" "${REQUEST_BODY_PATH}" > "${FAKE_CP_LOG_PATH}" 2>&1 &
    FAKE_CP_PID=$!

    if ! wait_http_any_status "http://127.0.0.1:${CONTROL_PLANE_PORT}/" 30 1; then
        err "Fake control-plane did not start on port ${CONTROL_PLANE_PORT}"
        if [[ -f "${FAKE_CP_LOG_PATH}" ]]; then
            cat "${FAKE_CP_LOG_PATH}" >&2 || true
        fi
        exit 1
    fi
}

write_test_function() {
    log "Preparing deploy spec and build context..."
    cat > "${WORK_DIR}/context/Dockerfile" <<'EOF'
FROM scratch
LABEL org.opencontainers.image.title="nanofaas-deploy-host-e2e"
EOF

    cat > "${WORK_DIR}/function.yaml" <<EOF
name: ${FUNCTION_NAME}
image: localhost:${REGISTRY_PORT}/${IMAGE_REPOSITORY}:${TAG}
executionMode: DEPLOYMENT
x-cli:
  build:
    context: ${WORK_DIR}/context
    dockerfile: ${WORK_DIR}/context/Dockerfile
    push: true
EOF
}

run_deploy() {
    log "Running: nanofaas deploy (build + push + register)..."
    "${CLI_BIN}" \
        --endpoint "http://127.0.0.1:${CONTROL_PLANE_PORT}" \
        deploy -f "${WORK_DIR}/function.yaml"
}

verify_registry_push() {
    log "Verifying image exists in registry..."
    local tags_json
    tags_json=$(curl -fsS "http://127.0.0.1:${REGISTRY_PORT}/v2/${IMAGE_REPOSITORY}/tags/list")
    echo "${tags_json}" | grep -q "\"${TAG}\"" || {
        err "Tag ${TAG} not found in registry response: ${tags_json}"
        exit 1
    }
}

verify_register_request() {
    log "Verifying control-plane register request..."
    [[ -s "${REQUEST_BODY_PATH}" ]] || {
        err "Missing register request body at ${REQUEST_BODY_PATH}"
        exit 1
    }

    grep -Eq "\"name\"[[:space:]]*:[[:space:]]*\"${FUNCTION_NAME}\"" "${REQUEST_BODY_PATH}" || {
        err "Function name not found in request body"
        cat "${REQUEST_BODY_PATH}" >&2
        exit 1
    }

    grep -Eq "\"image\"[[:space:]]*:[[:space:]]*\"localhost:${REGISTRY_PORT}/${IMAGE_REPOSITORY}:${TAG}\"" "${REQUEST_BODY_PATH}" || {
        err "Image not found in request body"
        cat "${REQUEST_BODY_PATH}" >&2
        exit 1
    }
}

main() {
    log "Host-only deploy E2E (no VM): docker buildx + push + apply"
    log "registry=localhost:${REGISTRY_PORT} image=${IMAGE_REPOSITORY}:${TAG}"

    check_prerequisites
    resolve_buildx_builder
    build_cli
    setup_workdir
    start_registry
    start_fake_control_plane
    write_test_function
    run_deploy
    verify_registry_push
    verify_register_request

    log "Deploy host E2E: PASSED"
}

main "$@"
