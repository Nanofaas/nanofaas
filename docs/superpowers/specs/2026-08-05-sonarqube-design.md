# Local SonarQube Analysis — Design

**Date:** 2026-08-05
**Status:** Revised 2026-08-05 after design review — approved for implementation planning

## Goal

Add on-demand, local SonarQube analysis for the nanofaas monorepo: a single
script (`scripts/sonar.sh`) that starts a local SonarQube container with Docker,
runs the analysis for Java + Python + Rust, and reports the issue counts per
language. Explicitly **not** a CI pipeline step: no integration in
`.github/workflows/gitops.yml`, analysis runs only when the developer invokes
the script.

## Current state

- Every Gradle subproject already applies JaCoCo 0.8.14 with XML reports
  enabled (`build.gradle`), so Java coverage artifacts for SonarQube are
  already produced by `./gradlew test`.
- No SonarQube/SonarCloud references exist anywhere in the repo; no
  docker-compose files.
- CI (`gitops.yml`) has three jobs (test-java, test-python, test-watchdog) —
  untouched by this change. None of them runs a linter (no ruff, no clippy),
  so Sonar's Python/Rust findings are genuinely new signal, not a duplicate
  gate.
- Language layout: Java in 10 Gradle modules (`platform/common`,
  `platform/control-plane`, `sdks/java`, `sdks/java-lite`, `services/java/warm-echo`,
  `clients/cli`, 4 `functions/java/*`), Python in `sdks/python`, Rust in
  `runtimes/watchdog`. `experiments/` is a research sandbox — out of scope.

## Why there is no quality gate verdict

The obvious design — ephemeral server, PASS/FAIL per language, `--fail-on-gate`
— does not work, and this is the central constraint of the whole feature.

With no volume there is no analysis history, so SonarQube treats **the entire
codebase as "new code"**. The default `Sonar way` gate evaluates its conditions
against new code: `new issues > 0` therefore fails on the first pre-existing
issue in the repo, on every run, forever. A gate verdict built this way is a
constant FAIL and carries no information; `--fail-on-gate` would be permanently
red and would be disabled by whoever runs it on day two.

Consequences, adopted below:

- The script reports **issue counts by severity per project**, not PASS/FAIL.
- There is no `--fail-on-gate` flag.
- The **server stays up** after the run. The value of SonarQube is the issue
  list in the UI; tearing the container down right after printing a verdict
  destroys the only useful output. State is still ephemeral (no volume) — the
  container is simply not killed while the developer is still reading it.

A real gate would require persistent history (a volume, a baseline, a custom
gate without new-code coverage conditions). That is out of scope; see below.

## Design

### 1. `scripts/sonar.sh`

```
Usage: scripts/sonar.sh [--rm] [--only java|python|rust]
```

| Step | Behavior |
|---|---|
| Preconditions | Docker daemon reachable; `sonar-scanner` on `PATH` (error prints `brew install sonar-scanner`); for Rust, `cargo` and `cargo clippy` available; port 127.0.0.1:9000 free — otherwise a clear error and exit 1 |
| Server start | `docker run -d --name sonar-nanofaas -p 127.0.0.1:9000:9000 sonarqube:26.7.0.124771-community` — pinned Community tag (there is no `lts` tag any more; `2026-lta-*` tags exist only for developer/enterprise). A preemptive `docker rm -f sonar-nanofaas` makes the script idempotent, so a container left running by the previous run is replaced, never accumulated. Reused as-is if already `UP` |
| Readiness | Poll `GET /api/system/status` every 5s until `status=UP`, timeout 300s (first boot after image pull is slow); on timeout dump the last 50 container log lines and exit 1 |
| Credentials | Generate a token once per run via `POST /api/user_tokens/generate` (admin/admin on a fresh server); pass it to every analysis via `-Dsonar.token`. **Highest-risk step — validate first against the pinned version**; recent releases may require the default password to be changed before the token API accepts admin/admin. Fallback if so: `POST /api/users/change_password` before generating |
| Java analysis | `./gradlew test --no-parallel sonar -Dsonar.projectKey=nanofaas-java ...` — reuses the per-module JaCoCo XMLs already produced by `test`; `--no-parallel` per project convention |
| Python analysis | `sonar-scanner` → project `nanofaas-python`, `sonar.sources=sdks/python` (static analysis only) |
| Rust analysis | `sonar-scanner` → project `nanofaas-rust`, `sonar.sources=runtimes/watchdog`, `sonar.rust.cargo.manifestPaths=runtimes/watchdog/Cargo.toml`. The Sonar Rust analyzer **runs Clippy itself** when it finds a manifest, so the Rust toolchain must be installed on the host running the scanner — this is why the scanner runs on the host and not in a container |
| Report | `GET /api/issues/search?componentKeys=<key>&resolved=false&facets=impactSeverities` per project → issue-count table per language, plus the UI URL. Confirm the facet name against the pinned version during implementation |
| Cleanup | None by default: the server stays up so the issues can be read, and the next run replaces it. `--rm` removes the container at the end |
| Exit code | 0 when every requested analysis was submitted; non-zero on infrastructure errors (Docker, readiness, token, scanner). Issue counts never affect the exit code |

Bash strict mode (`set -euo pipefail`). A failing analysis of one language does
not block the others — each project is reported with its own outcome.
Note: on macOS Docker Desktop no sysctl tuning is needed; on bare Linux hosts
`vm.max_map_count` must be raised (documented caveat, not handled by the
script).

`--only` exists because the three analyses are independent and Java is the slow
one (it re-runs the whole test suite).

### 2. Build changes

- `build.gradle`: add `id 'org.sonarqube' version "${sonarQubePluginVersion}"`
  to the root plugin block. **Applied to the root project only** — the plugin
  traverses subprojects itself and discovers modules, binaries and JaCoCo
  reports. Applying it inside `subprojects {}` would create ten `sonar` tasks,
  i.e. ten separate analyses overwriting each other under the same
  `sonar.projectKey`.
- `gradle.properties`: `sonarQubePluginVersion=<pin>` — resolve the exact
  version during planning and verify compatibility with Gradle 9.3.1 and the
  Java 25 toolchain.
- `.gitignore`: add `.scannerwork/` (created by the scanner CLI in the working
  directory).
- Coverage: Java only (JaCoCo already present). Python and Rust get static
  analysis only — no pytest-cov/llvm-cov work today, and none is added.

### 3. Documentation

- `CLAUDE.md`: one line in the Build & Development Commands block with the
  script usage.

### 4. Error handling & testing

- Readiness timeout dumps container logs for diagnosis; the container is left
  in place on failure precisely so `docker logs` still works.
- Checks: `bash -n` syntax gate. The first real run is the test: the script
  itself is the verification (server + full analysis in ~5 minutes). No
  automated CI-style SonarQube tests — that would reintroduce exactly the
  pipeline we are avoiding.
- Validation order during implementation, riskiest first: image tag pull →
  token generation → Gradle plugin single-analysis check → Rust analyzer with
  local clippy.

## Out of scope

- Any CI integration (push/PR triggers, secrets, blocking builds).
- Quality gate verdicts and gate-based exit codes (see the section above).
- Python/Rust coverage import.
- `experiments/` analysis.
- Persistent/history-backed analysis and true "new code" deltas (require a
  volume and a baseline — deliberately rejected).
- Self-hosted persistent server management.

## Open question

The Sonar Rust analyzer is essentially 85 Clippy rules surfaced in the Sonar
UI, and `cargo clippy` gives the same findings locally without a container.
The Rust project is kept in scope for a single aggregated dashboard, but if
that aggregation turns out not to be worth it, dropping `nanofaas-rust` also
drops the cargo/clippy precondition from the script.
