#!/usr/bin/env bash
# Local SonarQube analysis for the nanofaas monorepo.
# Starts an ephemeral SonarQube container, runs the requested analyses,
# prints issue counts per project and leaves the server up for browsing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

SONAR_HOST="http://127.0.0.1:9000"
SONAR_IMAGE="sonarqube:26.7.0.124771-community"
CONTAINER_NAME="sonar-nanofaas"
POLL_INTERVAL=5
START_TIMEOUT=300

DRY=false
KEEP=true
ONLY=""

usage() {
    cat <<'EOF'
Usage: scripts/sonar.sh [--rm] [--only java|python|rust] [--dry-run]

Runs SonarQube analysis for nanofaas (Java + Python + Rust by default)
against an ephemeral SonarQube container on 127.0.0.1:9000.

  --rm           remove the container when the run finishes (default:
                 leave it up so issues can be browsed; the next run
                 replaces it)
  --only LANG    analyse only java, python or rust
  --dry-run      print the commands without executing them (preconditions
                 are still checked, but nothing is started)
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rm) KEEP=false; shift ;;
        --dry-run) DRY=true; shift ;;
        --only)
            [[ $# -ge 2 ]] || { usage >&2; exit 1; }
            case "$2" in
                java|python|rust) ONLY="$2"; shift 2 ;;
                *) echo "Unknown language: $2" >&2; usage >&2; exit 1 ;;
            esac
            ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 1 ;;
    esac
done

run() {
    echo "\$ $*"
    if [ "$DRY" = false ]; then
        "$@"
    fi
}

# --- Preconditions -----------------------------------------------------------
command -v docker >/dev/null || { echo "docker not found on PATH" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon not reachable (docker info failed)" >&2; exit 1; }
command -v sonar-scanner >/dev/null || {
    echo "sonar-scanner not found on PATH. Install it: brew install sonar-scanner" >&2; exit 1
}
command -v python3 >/dev/null || { echo "python3 not found on PATH" >&2; exit 1; }
if [ "$ONLY" = "rust" ] || [ -z "$ONLY" ]; then
    command -v cargo >/dev/null || { echo "cargo not found on PATH (needed for Rust analysis)" >&2; exit 1; }
    cargo clippy --version >/dev/null 2>&1 || {
        echo "cargo clippy unavailable. Install it: rustup component add clippy" >&2; exit 1
    }
fi
cleanup() {
    if [ "$KEEP" = false ] && [ "$DRY" = false ]; then
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
# --- Server lifecycle --------------------------------------------------------
if [ "$DRY" = false ]; then
    if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
        if curl -sf "$SONAR_HOST/api/system/status" 2>/dev/null | python3 -c \
            'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status")=="UP" else 1)'; then
            echo "Reusing running container ${CONTAINER_NAME}"
        else
            docker rm -f "$CONTAINER_NAME" >/dev/null
        fi
    fi
    if ! docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
        if lsof -iTCP:9000 -sTCP:LISTEN >/dev/null 2>&1; then
            echo "Port 9000 is already in use; release it or remove the container holding it" >&2
            exit 1
        fi
        run docker run -d --name "$CONTAINER_NAME" -p 127.0.0.1:9000:9000 "$SONAR_IMAGE"
    fi

    echo "Waiting for SonarQube on ${SONAR_HOST} (timeout ${START_TIMEOUT}s)..."
    deadline=$((SECONDS + START_TIMEOUT))
    while ! curl -sf "$SONAR_HOST/api/system/status" 2>/dev/null | python3 -c \
        'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status")=="UP" else 1)'; do
        if (( SECONDS >= deadline )); then
            echo "SonarQube not UP after ${START_TIMEOUT}s; last container logs:" >&2
            docker logs --tail 50 "$CONTAINER_NAME" >&2 || true
            exit 1
        fi
        sleep "$POLL_INTERVAL"
    done
fi

# --- Credentials -------------------------------------------------------------
TOKEN=""
TOKEN_NAME="nanofaas-run-$(date +%s)"   # unique: reused servers reject duplicate token names
if [ "$DRY" = false ]; then
    if ! TOKEN="$(curl -sf -u admin:admin -X POST "$SONAR_HOST/api/user_tokens/generate" \
        -d "name=${TOKEN_NAME}" -d "type=USER_TOKEN" | python3 -c \
        'import json,sys; print(json.load(sys.stdin)["token"])' 2>/dev/null)"; then
        echo "Token generation with admin/admin rejected; changing admin password first..." >&2
        NEW_PASS="Nanofaas$(date +%s)!"
        curl -sf -u admin:admin -X POST "$SONAR_HOST/api/users/change_password" \
            -d "login=admin" -d "previousPassword=admin" -d "password=${NEW_PASS}" >/dev/null || {
            echo "Failed to reset the admin password; the reused server may no longer use admin/admin." >&2
            echo "Remove the container to restore a fresh server: docker rm -f ${CONTAINER_NAME}" >&2
            exit 1
        }
        TOKEN="$(curl -sf -u "admin:${NEW_PASS}" -X POST "$SONAR_HOST/api/user_tokens/generate" \
            -d "name=${TOKEN_NAME}" -d "type=USER_TOKEN" | python3 -c \
            'import json,sys; print(json.load(sys.stdin)["token"])')"
    fi
    [ -n "$TOKEN" ] || { echo "Failed to obtain an API token" >&2; exit 1; }
fi
# --- Analyses ----------------------------------------------------------------
run_java() {
    run ./gradlew test --no-parallel sonar \
        -Dsonar.host.url="$SONAR_HOST" -Dsonar.token="$TOKEN" \
        -Dsonar.projectKey=nanofaas-java -Dsonar.projectName="nanofaas Java"
}

run_python() {
    run sonar-scanner \
        -Dsonar.host.url="$SONAR_HOST" -Dsonar.token="$TOKEN" \
        -Dsonar.projectKey=nanofaas-python -Dsonar.projectName="nanofaas Python" \
        -Dsonar.sources=sdks/python \
        -Dsonar.exclusions="**/__pycache__/**,**/*.pyc"
}

run_rust() {
    run sonar-scanner \
        -Dsonar.host.url="$SONAR_HOST" -Dsonar.token="$TOKEN" \
        -Dsonar.projectKey=nanofaas-rust -Dsonar.projectName="nanofaas Rust" \
        -Dsonar.sources=runtimes/watchdog \
        -Dsonar.rust.cargo.manifestPaths=runtimes/watchdog/Cargo.toml
}

FAILED=""
if [ -n "$ONLY" ]; then
    eval "RUN_${ONLY}=true"
else
    RUN_java=true; RUN_python=true; RUN_rust=true
fi

if [ "${RUN_java:-false}" = true ] && ! run_java; then FAILED="${FAILED} java"; fi
if [ "${RUN_python:-false}" = true ] && ! run_python; then FAILED="${FAILED} python"; fi
if [ "${RUN_rust:-false}" = true ] && ! run_rust; then FAILED="${FAILED} rust"; fi
# --- Report ------------------------------------------------------------------
wait_for_analysis() {
    # The scanner exits as soon as the report is uploaded; issues are only
    # queryable once the Compute Engine has processed it. Wait for CE first.
    local key="$1" status="" deadline=$((SECONDS + 180))
    while true; do
        status="$(curl -sf -u "$TOKEN": "$SONAR_HOST/api/ce/component?component=${key}" 2>/dev/null \
            | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("current") or {}).get("status",""))' 2>/dev/null || true)"
        case "$status" in
            SUCCESS) return 0 ;;
            FAILED|CANCELED)
                echo "  WARNING: background analysis task for ${key} finished with status ${status}" >&2
                return 1 ;;
        esac
        if (( SECONDS >= deadline )); then
            echo "  WARNING: analysis task for ${key} still ${status:-unknown} after 180s" >&2
            return 1
        fi
        sleep 2
    done
}

report() {
    local key="$1" name="$2"
    local counts
    if [ "$DRY" = false ]; then
        if ! wait_for_analysis "$key"; then return 1; fi
        if ! counts="$(curl -sf -u "$TOKEN": \
            "$SONAR_HOST/api/issues/search?componentKeys=${key}&resolved=false&ps=1&facets=impactSeverities" \
            | python3 -c '
import json, sys
d = json.load(sys.stdin)
for f in d.get("facets", []):
    if f["property"] in ("impactSeverities", "severities"):
        counts = {v["val"]: v["count"] for v in f["values"]}
        total = d.get("paging", {}).get("total", 0)
        order = ["CRITICAL", "HIGH", "MEDIUM", "LOW"]
        # impactSeverities reports BLOCKER as its top level; surface it in the CRITICAL slot
        if f["property"] == "impactSeverities" and counts.get("BLOCKER", 0):
            counts["CRITICAL"] = counts["BLOCKER"]
        parts = [f"{sev}={counts.get(sev, 0)}" for sev in order]
        print(f"{total}|" + ",".join(parts))
        sys.exit(0)
sys.exit(1)
')"; then
            echo "  WARNING: could not fetch issue counts (facet/API mismatch?)" >&2
            return 1
        fi
        local total="${counts%%|*}"
        local breakdown="${counts#*|}"
        echo "  ${name}: ${total} open issues (${breakdown//,/ })"
        echo "    ${SONAR_HOST}/project/issues?resolved=false&id=${key}"
    fi
}

echo "=== SonarQube results ==="
REPORT_FAILED=false
if [ -n "$ONLY" ]; then
    if ! report "nanofaas-${ONLY}" "${ONLY}"; then REPORT_FAILED=true; fi
else
    for pair in "nanofaas-java java" "nanofaas-python python" "nanofaas-rust rust"; do
        set -- $pair
        if ! report "$1" "$2"; then REPORT_FAILED=true; fi
    done
fi

if [ -n "$FAILED" ]; then
    echo "Analyses failed:${FAILED}" >&2
    exit 1
fi
if [ "$REPORT_FAILED" = true ]; then exit 1; fi
if [ "$DRY" = false ] && [ "$KEEP" = true ]; then
    echo
    echo "Server left running at ${SONAR_HOST} (UI: ${SONAR_HOST})."
    echo "Remove it any time with: docker rm -f ${CONTAINER_NAME}"
    echo "Or pass --rm next run."
fi
exit 0
