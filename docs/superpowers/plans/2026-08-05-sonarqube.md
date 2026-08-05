# Local SonarQube Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On-demand, local SonarQube analysis for the nanofaas monorepo via `scripts/sonar.sh`: an ephemeral Docker server, analysis for Java + Python + Rust, issue counts per language reported.

**Architecture:** The script owns the full lifecycle — an idempotent `docker run` of a pinned SonarQube Community image, a readiness poll, one-time admin token bootstrap, then three independent analyses (Java via the root-applied Gradle `org.sonarqube` plugin reusing existing JaCoCo XMLs; Python and Rust via the `sonar-scanner` CLI on the host, where the Rust analyzer invokes Clippy itself). No quality gate verdicts: with an ephemeral server everything is "new code" and the default gate is permanently FAIL, so the script reports issue counts per severity and leaves the server up for browsing. No CI integration.

**Tech Stack:** Bash (strict mode), Docker, SonarQube Community image `sonarqube:26.7.0.124771-community`, Gradle plugin `org.sonarqube` 7.3.1.8318, `sonar-scanner` CLI on PATH, Python 3 for JSON parsing.

## Global Constraints

- Image pin: `sonarqube:26.7.0.124771-community` (there is no `lts` tag anymore).
- Gradle plugin pin: `sonarQubePluginVersion=7.3.1.8318` — applied to the **root project only** (applying inside `subprojects {}` would create ten `sonar` tasks overwriting each other under the same `sonar.projectKey`).
- Gradle 9.3.1 wrapper, Java 25 toolchain; tests run with `--no-parallel` (project convention).
- `sonar-scanner` must be on PATH (precondition; error message suggests `brew install sonar-scanner`). Rust analysis additionally requires `cargo` + `cargo clippy` on the host — the scanner runs on the host, **not** in a container.
- Server state is ephemeral (no volume). The server **stays up** after the run so issues can be browsed; `--rm` removes it; the next run replaces a stale container.
- Port `127.0.0.1:9000`; readiness timeout 300s, poll interval 5s.
- Credentials: admin/admin on a fresh server; token via `POST /api/user_tokens/generate`; fallback `POST /api/users/change_password` if the token API rejects the default password.
- Issue counts never affect the exit code; non-zero only on infrastructure errors or a failed analysis submission. A failing analysis of one language does not block the others.
- Coverage: Java only (JaCoCo already present). Python/Rust static analysis only. `experiments/` out of scope.
- No quality gate verdicts, no CI integration, no volume/baseline.
- Commits in this repo carry **no** `Co-Authored-By` trailer.

---

### Task 1: Build plumbing (plugin, ignore, docs)

**Files:**
- Modify: `gradle.properties` (add version pin)
- Modify: `build.gradle` (plugins block)
- Modify: `.gitignore` (add `.scannerwork/`)
- Modify: `CLAUDE.md` (one usage line)

**Interfaces:**
- Produces: root Gradle project with a single `sonar` task, runnable via `./gradlew sonar -Dsonar.host.url=... -Dsonar.token=... -Dsonar.projectKey=...`. Task 2 consumes this.

- [ ] **Step 1: Pin the plugin version**

In `gradle.properties`, next to the other version pins (after `graalvmJavaVersion=25.0.4`), add:

```properties
sonarQubePluginVersion=7.3.1.8318
```

- [ ] **Step 2: Apply the plugin at the root project only**

In `build.gradle`, extend the `plugins {}` block (lines 1-7) so it reads:

```groovy
plugins {
    id 'java'
    // Declare plugin versions once to avoid Gradle classloader/build-service issues across sibling projects.
    id 'org.springframework.boot' version "${springBootVersion}" apply false
    id 'io.spring.dependency-management' version "${springDependencyManagementVersion}" apply false
    id 'org.graalvm.buildtools.native' version "${graalvmBuildToolsVersion}" apply false
    id 'org.sonarqube' version "${sonarQubePluginVersion}"
}
```

Note the deliberate difference: `org.sonarqube` has **no** `apply false` — it is applied to the root project, from where it traverses subprojects and discovers modules, binaries and JaCoCo reports by itself. Do NOT add `apply plugin: 'org.sonarqube'` inside `subprojects {}`.

- [ ] **Step 3: Ignore the scanner work directory**

In `.gitignore`, add:

```
.scannerwork/
```

- [ ] **Step 4: Document the script in CLAUDE.md**

In `CLAUDE.md`, under `## Build & Development Commands`, after the `./scripts/native-build.sh` line, add:

```markdown
# Local SonarQube analysis (ephemeral Docker server, on demand — not CI)
./scripts/sonar.sh [--rm] [--only java|python|rust]
```

- [ ] **Step 5: Verify exactly one sonar task exists**

Run: `./gradlew tasks --all | grep -i sonar`

Expected: exactly one `sonar -` task line (plus `sonarProperties`/`sonarCacheableFiles` auxiliary tasks) at the root. If you see ten `sonar` lines, the plugin got applied per-subproject — fix Step 2 before continuing.

- [ ] **Step 6: Commit**

```bash
git add gradle.properties build.gradle .gitignore CLAUDE.md
git commit -m "build: add sonarqube gradle plugin (root-only) and scannerwork ignore"
```

---

### Task 2: `scripts/sonar.sh`

**Files:**
- Create: `scripts/sonar.sh` (executable)

**Interfaces:**
- Consumes: Task 1's root `sonar` task; `sonar-scanner` on PATH; `docker`; `python3`; `cargo` + `cargo clippy` (Rust); port 127.0.0.1:9000.
- Produces: the complete script with flags `--rm`, `--only java|python|rust`, `--dry-run`. Task 3 executes it.

- [ ] **Step 1: Create the script skeleton with flags, preconditions and usage**

Create `scripts/sonar.sh` with this full content (sections are built in the following steps — write the whole file now):

```bash
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
if [ "$DRY" = false ] && lsof -iTCP:9000 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port 9000 is already in use; release it or use SONAR_HOST elsewhere" >&2
    exit 1
fi
```

The `--only` selection below uses `eval "RUN_${ONLY}=true"`; the other `RUN_*` variables are simply left unset and read with `${RUN_java:-false}` defaults — do not initialize them with `RUN_$lang=false`, which is not a valid bash assignment.

- [ ] **Step 2: Add the server lifecycle (start, reuse, readiness, cleanup)**

Append to the file:

```bash
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

cleanup() {
    if [ "$KEEP" = false ] && [ "$DRY" = false ]; then
        docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
```

- [ ] **Step 3: Add token bootstrap with password-change fallback**

Append:

```bash
# --- Credentials -------------------------------------------------------------
TOKEN=""
if [ "$DRY" = false ]; then
    if ! TOKEN="$(curl -sf -u admin:admin -X POST "$SONAR_HOST/api/user_tokens/generate" \
        -d "name=nanofaas-run" -d "type=GLOBAL" | python3 -c \
        'import json,sys; print(json.load(sys.stdin)["token"])' 2>/dev/null)"; then
        echo "Token generation with admin/admin rejected; changing admin password first..." >&2
        NEW_PASS="Nanofaas$(date +%s)"
        curl -sf -u admin:admin -X POST "$SONAR_HOST/api/users/change_password" \
            -d "login=admin" -d "previousPassword=admin" -d "password=${NEW_PASS}" >/dev/null
        TOKEN="$(curl -sf -u "admin:${NEW_PASS}" -X POST "$SONAR_HOST/api/user_tokens/generate" \
            -d "name=nanofaas-run" -d "type=GLOBAL" | python3 -c \
            'import json,sys; print(json.load(sys.stdin)["token"])')"
    fi
    [ -n "$TOKEN" ] || { echo "Failed to obtain an API token" >&2; exit 1; }
fi
```

- [ ] **Step 4: Add the three analyses**

Append:

```bash
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
```

- [ ] **Step 5: Add the report and the final exit code**

Append:

```bash
# --- Report ------------------------------------------------------------------
report() {
    local key="$1" name="$2"
    local counts
    if [ "$DRY" = false ]; then
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
```

- [ ] **Step 6: Make it executable, syntax-gate and dry-run smoke**

```bash
chmod +x scripts/sonar.sh
bash -n scripts/sonar.sh
./scripts/sonar.sh --dry-run
```

Expected: `bash -n` silent; `--dry-run` prints the three analysis commands without executing anything (preconditions are still checked; the docker run line is not echoed — the server-lifecycle block is gated by `DRY=false`), exit 0.

- [ ] **Step 7: Commit**

```bash
git add scripts/sonar.sh
git commit -m "feat: add local sonarqube analysis script"
```

---

### Task 3: Live validation (riskiest first)

**Files:** `scripts/sonar.sh` (fixes only, if surfaced)

**Interfaces:**
- Consumes: the complete script from Task 2. Docker daemon running; `sonar-scanner` and `python3` on PATH; `cargo` + `cargo clippy` installed.

The script itself is the test (per spec: no automated SonarQube tests — that would reintroduce the CI pipeline being avoided). Validation order per spec, riskiest first: image tag pull → token generation → Gradle plugin single-analysis → Rust analyzer with local clippy. Each run below is independent (fresh ephemeral server, admin/admin again).

- [ ] **Step 1: Python analysis (covers image pull, readiness, token, facet)**

Run: `./scripts/sonar.sh --only python`

Expected: container pulled/started, readiness poll reaches UP, token generated (no password-change fallback), analysis submits, report line `python: N open issues (CRITICAL=..,HIGH=..,MEDIUM=..,LOW=..)` plus UI URL, server left running.

If the token API rejects admin/admin, the script's fallback must engage (password change + retry) — confirm the output shows the fallback message and still succeeds. If the facet `impactSeverities` is missing, the python3 snippet falls back to `severities` — check the breakdown prints non-zero severities, not empty.

- [ ] **Step 2: Java analysis (Gradle plugin single-analysis + JaCoCo)**

Run: `./scripts/sonar.sh --only java`

Expected: full test suite runs (`--no-parallel`), then `sonar` with one analysis under `nanofaas-java`; report shows the issue counts and the UI URL. If the plugin version is incompatible with Gradle 9.3.1/Java 25 (task fails at configuration), fix by bumping `sonarQubePluginVersion` in `gradle.properties` to the latest on <https://plugins.gradle.org/plugin/org.sonarqube> and re-run.

- [ ] **Step 3: Rust analysis (Clippy via the Sonar analyzer)**

Run: `./scripts/sonar.sh --only rust`

Expected: `sonar-scanner` runs, finds the Cargo manifest, invokes clippy, reports counts for `nanofaas-rust`. If the Rust analyzer errors on the manifest path property, check the exact property spelling in the Sonar docs (<https://docs.sonarsource.com/sonarqube-server/2025.4/analyzing-source-code/languages/rust>) and fix the script. While here, resolve the spec's open question: if the Rust findings are only clippy rules already visible locally via `cargo clippy`, dropping `nanofaas-rust` (and its cargo/clippy preconditions) is acceptable — decide with the user, do not unilaterally.

- [ ] **Step 4: Full run + cleanup check**

Run: `./scripts/sonar.sh --rm`

Expected: all three analyses, three report lines, then the container is removed (`docker ps -a | grep sonar-nanofaas` → empty). Repeat once without `--rm`, then run again: the second run must print "Reusing running container" and not restart it.

- [ ] **Step 5: Commit any surfaced fixes**

```bash
git add scripts/sonar.sh gradle.properties
git commit -m "fix: sonarqube script validation fixes"
```

(Only if Step 1-4 produced changes; otherwise skip.)
