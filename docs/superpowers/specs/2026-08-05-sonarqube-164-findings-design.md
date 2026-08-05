# Issue #164 — SonarQube BLOCKER + CRITICAL Findings Fix — Design

**Date:** 2026-08-05
**Status:** Approved for implementation planning

## Goal

Resolve all 47 findings of GitHub issue #164 (SonarQube Java, BLOCKER + CRITICAL
severities, legacy classification), extracted from the on-demand local analysis
(`./scripts/sonar.sh`, SonarQube Community 26.7.0.124771). After the fixes land,
a fresh SonarQube run must report 0 issues in the `nanofaas-java` project for
these 47.

## Current state

- The findings live in issue #164 (3 issues created: #164/#165/#166, split by
  severity band). This spec covers only #164.
- The 47 findings group into 8 rule categories (see Triage below). Two of them
  are genuine defects (S2095 resource leak, S2274 spurious-wakeup risk); the
  rest are test hygiene, duplicated literals, API type shapes, and cognitive
  complexity.
- SonarQube server for local verification is up at `127.0.0.1:9000` but its
  admin password was changed by the user during UI login; `./scripts/sonar.sh`
  only works from a fresh container (`docker rm -f sonar-nanofaas` first, which
  resets to `admin/admin`).
- Repo conventions: Java 25, Gradle 9.3.1, 4-space indentation,
  `it.unimib.datai.nanofaas` package root, tests run with
  `./gradlew test --no-parallel`, no Co-Authored-By trailers in commits.

## Triage (47 findings)

| Rule | Count | Severity | What it is | Fix approach |
|---|---|---|---|---|
| `java:S2699` | 6 | BLOCKER | Tests with no assertions (branch/error-path tests) | Add the correct assertion per test after reading each one (`assertThrows`, `verify`, `assertDoesNotThrow` as appropriate) |
| `java:S2095` | 1 | BLOCKER | `CreateContainerCmd` never closed in `DockerJavaContainerRuntimeAdapter.runContainer` (`platform/modules/container-deployment-provider/.../DockerJavaContainerRuntimeAdapter.java:63`) | try-with-resources around the command (docker-java commands are `Closeable`); semantics unchanged |
| `java:S2274` | 1 | CRITICAL | Bare `wait(timeoutMs)` in `SyncQueueService.awaitWork` (`platform/modules/sync-queue/.../SyncQueueService.java:116`) | `if (queuedItems() == 0)` → `while`; predicate re-check absorbs spurious wakeups, bounded wait preserved (`queue` lock ≠ `workSignal` lock, no deadlock) |
| `java:S1192` | 23 | CRITICAL | Duplicated string literals ("error", "count", "function", "success", "v1/functions/", "application/json", "Content-Type", "queue_depth", "nanofaas", "executionId", "traceId", "runtime_invocations_total", "remote ") | `private static final String` constants in the owning class, exact locations per the issue listing |
| `java:S1452` | 6 | CRITICAL | Generic wildcard types in public return types (`FunctionController` ×3, `AdminRuntimeConfigController` ×2, `HealthController` ×1) | Replace wildcards with explicit types; exact signatures to be read from the files during planning |
| `java:S1186` | 3 | CRITICAL | Empty methods in `CoreDefaultsTest` (intentional no-op config overrides) | Nested comment explaining why the override is empty |
| `java:S2093` | 1 | CRITICAL | `try` on a closeable in `RootCommandTest:138` | try-with-resources |
| `java:S3776` | 6 | CRITICAL | Cognitive complexity over 15 in: `ExecutionCompletionHandler` :180 (19) and :244 (18), `KubernetesImageValidator:120` (18), `KubernetesResourceManager:41` (20), `CallbackClient` (java-lite) :46 (21), `CallbackClient` (java) :62 (17) | Extract-method refactors, behavior-preserving; the documented lock invariant in `ExecutionCompletionHandler` (state transitions under the record monitor; meter recording and future completion in `publishFinalCompletion` outside it) MUST be preserved — see the comment block above `completeUnderLock` |

## Global constraints

- `./gradlew test --no-parallel` green after each task (full suite — shared
  signatures like `CallbackClient` touch multiple modules; control-plane-only
  test runs are not sufficient).
- Impact analysis via GitNexus (`gitnexus_impact`) before editing any symbol;
  HIGH/CRITICAL risk must be surfaced to the user before proceeding.
- No `@SuppressWarnings` for any of these findings (S3776 included — user
  chose refactor, not suppression).
- No unrelated refactoring; each change addresses exactly its finding.
- No Co-Authored-By trailers in commits.

## Verification

1. Per task: full test suite green.
2. Final (after merge): `docker rm -f sonar-nanofaas && ./scripts/sonar.sh` —
   the `nanofaas-java` project must report 0 issues for the 47 finding
   locations (new findings introduced by the fixes are in scope to fix too).
3. Update issue #164 with a summary of what was fixed (counts per rule).

## Out of scope

- Issues #165 (MAJOR, 220) and #166 (MINOR + INFO, 184) — separate efforts.
- Python and Rust findings.
- Quality-gate configuration, CI integration.
