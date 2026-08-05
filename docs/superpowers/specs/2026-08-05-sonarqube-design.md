# Local SonarQube Analysis — Design

**Date:** 2026-08-05
**Status:** Approved for implementation planning

## Goal

Add on-demand, local SonarQube analysis for the nanofaas monorepo: a single
script (`scripts/sonar.sh`) that starts an ephemeral SonarQube container with
Docker, runs the analysis for Java + Python + Rust, reports the quality gate
verdict, and tears the container down. Explicitly **not** a CI pipeline step:
no integration in `.github/workflows/gitops.yml`, analysis runs only when the
developer invokes the script.

## Current state

- Every Gradle subproject already applies JaCoCo 0.8.14 with XML reports
  enabled (`build.gradle`), so Java coverage artifacts for SonarQube are
  already produced by `./gradlew test`.
- No SonarQube/SonarCloud references exist anywhere in the repo; no
  docker-compose files.
- CI (`gitops.yml`) has three jobs (test-java, test-python, test-watchdog) —
  untouched by this change.
- Language layout: Java in 10 Gradle modules (`platform/common`,
  `platform/control-plane`, `sdks/java`, `sdks/java-lite`, `services/java/warm-echo`,
  `clients/cli`, 4 `functions/java/*`), Python in `sdks/python`, Rust in
  `runtimes/watchdog`. `experiments/` is a research sandbox — out of scope.

## Design

### 1. `scripts/sonar.sh`

```
Usage: scripts/sonar.sh [--keep-server] [--fail-on-gate] [--dry-run]
```

| Step | Behavior |
|---|---|
| Preconditions | Docker daemon reachable; port 127.0.0.1:9000 free; otherwise a clear error and exit 1 |
| Server start | `docker run -d --name sonar-nanofaas -p 127.0.0.1:9000:9000 sonarqube:lts` — fixed container name with a preemptive `docker rm -f sonar-nanofaas` makes the script idempotent; no volume, so state is ephemeral and every run analyses the whole codebase as "new code" |
| Readiness | Poll `GET /api/system/status` every 5s until `status=UP`, timeout 180s; on timeout dump the last 50 container log lines and exit 1 |
| Credentials | Generate a token once per run via `POST /api/user_tokens/generate` (admin/admin on a fresh server); pass it to every analysis via `-Dsonar.token` |
| Scanner CLI | Pinned `sonar-scanner-cli` zip, cached in `~/.cache/sonar-scanner/<ver>/`, downloaded only if missing |
| Java analysis | `./gradlew test --no-parallel sonar -Dsonar.projectKey=nanofaas-java ...` — reuses the per-module JaCoCo XMLs already produced by `test`; `--no-parallel` per project convention |
| Python analysis | scanner CLI → project `nanofaas-python`, `sonar.sources=sdks/python` (static analysis only) |
| Rust analysis | scanner CLI → project `nanofaas-rust`, `sonar.sources=runtimes/watchdog` (static analysis only; Rust is supported in SonarQube Community) |
| Verdict | `GET /api/qualitygates/project_status?projectKey=...` for each of the three projects → PASS/FAIL table per language |
| Cleanup | `trap EXIT` → `docker rm -f sonar-nanofaas` (never leaves orphans, also on failure); `--keep-server` leaves the container up and prints the UI URL |
| Exit code | 0 on success; non-zero on infrastructure errors; with `--fail-on-gate`, also non-zero when any gate is FAIL (default: report only) |
| `--dry-run` | Prints the commands without executing them; requires no Docker |

Bash strict mode (`set -euo pipefail`). A failing analysis of one language does
not block the others — each project is reported with its own outcome.
Note: on macOS Docker Desktop no sysctl tuning is needed; on bare Linux hosts
`vm.max_map_count` must be raised (documented caveat, not handled by the
script).

### 2. Build changes

- `build.gradle`: add `id 'org.sonarqube' version "${sonarQubePluginVersion}" apply false`
  to the plugin block; `apply plugin: 'org.sonarqube'` in `subprojects` (all 10
  Java modules analysed; the plugin auto-discovers modules, binaries and
  JaCoCo reports).
- `gradle.properties`: `sonarQubePluginVersion=6.1.0` (pin re-checked during
  planning — must be compatible with Gradle 9 and the Java 25 toolchain).
- Coverage: Java only (JaCoCo already present). Python and Rust get static
  analysis only — no pytest-cov/llvm-cov work today, and none is added.

### 3. Documentation

- `CLAUDE.md`: one line in the Build & Development Commands block with the
  script usage.

### 4. Error handling & testing

- Cleanup is guaranteed by `trap EXIT`; readiness timeout dumps container logs
  for diagnosis.
- Checks: `bash -n` syntax gate plus a `--dry-run` smoke (no Docker needed).
  The first real run is the test: the script itself is the verification
  (server + full analysis in ~5 minutes). No automated CI-style SonarQube
  tests — that would reintroduce exactly the pipeline we are avoiding.

## Out of scope

- Any CI integration (push/PR triggers, secrets, blocking builds).
- Python/Rust coverage import.
- `experiments/` analysis.
- Persistent/history-backed analysis ("new code" deltas require a volume,
  deliberately rejected).
- Self-hosted persistent server management.
