# SonarQube Issue #165 — 220 MAJOR Findings: Design

**Date:** 2026-08-05
**Context:** Issue #164 (47 BLOCKER+CRITICAL) fixed and closed 2026-08-05. Issue #165 holds the 220 MAJOR findings from the same on-demand local analysis (`./scripts/sonar.sh`, SonarQube Community 26.7.0.124771, legacy severities via API — MAJOR maps to MEDIUM impact severity in the UI).

## Goal

Close all 220 MAJOR findings on `nanofaas-java` with behavior-preserving changes, verified by a fresh local SonarQube analysis showing **0 MAJOR** and no new findings versus the baseline.

## Current State

- 220 open MAJOR findings across 27 rules, ~48 files. All are code smells — the real defects were in the #164 tranche.
- Server: container `sonar-sonata` (sonarqube:26.7.0.124771-community) holds port 9000 with the `nanofaas-java` project already analyzed at 404 open issues (220 MAJOR + 182 MINOR + 2 INFO). Reuse it for verification; do NOT delete it (not ours).

## Triage Table

Approach per rule. `N` = findings. Severity/type: all MAJOR code smells.

### Mechanical renames (103)

| Rule | N | What | Approach |
|---|---|---|---|
| S6213 | 93 | `record` used as variable/parameter/method name (restricted identifier). 90 variables + 3 test helper methods, all in `platform/` (86 control-plane, 5 sync-queue, 2 async-queue, 22 files). Typical: `ExecutionRecord record = ...` | Rename to `executionRecord` (variables, parameters, and the 3 test helper methods, e.g. `record(...)` in `ExecutionCompletionHandlerOffloadTest`). Naming consistent with the #164 fix round. |
| S1117 | 10 | Parameter/method-local shadowing the `meters` field in `Metrics.java` (lines 35-114, likely the `FunctionMeters meters(...)` accessor chain) | Rename the local/parameter so it no longer hides the field. |

### Test hygiene (69)

| Rule | N | What | Approach |
|---|---|---|---|
| S5778 | 37 | Test lambdas with more than one invocation that can throw a runtime exception inside `assertThatCode`/`assertThatThrownBy` blocks (14 test files: offload, image-validator, container-deployment-provider, control-plane service tests, sdks) | Refactor each lambda to contain a single invocation; hoist the rest out of the lambda. Behavior of the assertions unchanged. |
| S2925 | 23 | `Thread.sleep` in tests (14 test files across control-plane ×9, sdks/java ×5, sdks/java-lite ×2, sync-queue ×1, autoscaler ×1) | **Decision (user): Awaitility.** Replace sleeps with `await().atMost(...).until(...)`. `testImplementation 'org.awaitility:awaitility:4.2.1'` must be added to control-plane, sdks/java, sdks/java-lite (already present in async-queue, k8s-deployment-provider, sync-queue, autoscaler). |
| S5976 | 4 | "Replace these 3/4 tests with a single Parameterized one": `CallbackClientTest:138` (3), `DeployCommandTest:37` (3), `JsonTransformHandlerTest:89` (3), `IssueCoverageTest:47` (4) | Merge into `@ParameterizedTest` with `@CsvSource`/`@MethodSource` per the existing pattern in each test file. |
| S108 | 3 | Empty blocks in tests: `ContainerLocalDeploymentProviderTest:397` (`catch (Exception ignored) {}`), `RuntimeConfigServiceTest:86` (`catch (InterruptedException ignored) {}`), `TraceLoggingFilterHeaderPriorityTest:88` (`catch (ServletException ignored) {}`) | Add an explanatory comment inside each empty catch (`// ignore: ...`). |
| S5738 | 2 | Calls to deprecated-removal API: `DockerJavaRuntimeHints:32` (deprecated field), `CallbackClientTest:351` (java) (deprecated method) | Replace with the modern alternative if one exists; otherwise @SuppressWarnings("java:S5738") with a comment (deprecated API with no replacement). |

### Point fixes — small rules (28)

| Rule | N | Where | Approach |
|---|---|---|---|
| S8786 | 3 | Regexes with super-linear backtracking: `ContainerLocalDeploymentProvider:256`, `KubernetesImageValidator:200,209` | Simplify the regex (possessive quantifiers / atomic groups / restructure) preserving matched language. |
| S6885 | 3 | `Math.min`/`Math.max` pairs: `ScalingDecisionCalculator:34`, `FunctionSpecResolver:128`, `AdaptivePerPodConcurrencyController:79` | Replace with `Math.clamp` (Java 21+, toolchain is Java 25). |
| S6355 | 3 | `@Deprecated` without `since`/`forRemoval`: `FunctionQueueState:90,98,106` | Add `since` and/or `forRemoval` arguments documenting intent. |
| S1172 | 3 | Unused `args` parameter in `main` of example services: `RomanNumeralLite:17`, `JsonTransformLite:26`, `WordStatsLite:15` | **Suppression required**: the `main(String[] args)` signature is mandated by the JVM launcher; removing the parameter breaks standalone execution. `@SuppressWarnings("java:S1172")` with a comment. |
| S112 | 3 | Generic exception throws: `CallbackClient:96` (lite), `InvokeHandler:161` (lite), `HandlerExecutor:32` (java) | Throw a specific library/custom exception (e.g. `IllegalStateException`, `IllegalArgumentException`, or a dedicated type) preserving the thrown-context contract. |
| S1068 | 3 | Unused private fields: `CallbackClientTest:145` (lite, `EMPTY_MAP`), `InternalScaler:25` (`metricsReader`), `KubernetesResourceManager:24` (`properties`) | Remove the field. If a field is retained deliberately (test fixture), document and suppress instead — decide per site. |
| S6863 | 2 | `FunctionController:60,78` — "Set a HttpStatus code reflective of the operation" | Inspect the two handler methods; set an explicit, operation-appropriate status where the current response is ambiguous (exact fix determined in the plan from the full controller code). |
| S6829 | 2 | `@Autowired` missing on sole constructors: `IdempotencyStore:14`, `KubernetesClientConfig:20` | Add `@Autowired` to the constructor (explicit single-constructor wiring; safe on Spring Boot 4.1). |
| S1854 | 2 | Dead stores to `scaled`: `InternalScaler:151,162` | Remove the useless assignment (verify `scaled` is otherwise unused on that path). |
| S1141 | 2 | Nested try blocks: `InvokeHandler:112` (lite), `InternalScaler:123` | Extract the inner try into a separate method. |
| S1871 | 1 | Duplicate branches: `FunctionQueueState:118` — both the `if (effectiveConcurrency == previousConfigured)` and the `else if (effectiveConcurrency > normalized)` branch assign `effectiveConcurrency = normalized` | Merge into a single condition `if (effectiveConcurrency == previousConfigured \|\| effectiveConcurrency > normalized)` — behavior identical (verified: both branches assign the same value; the fixed-mode comment stays). |
| S2446 | 1 | `SyncQueueService:102` — `notify()` may not wake the appropriate thread | Replace with `notifyAll()` (single condition-wait queue, safe and standard). |

### Single-point fixes (20)

| Rule | N | Where | Approach |
|---|---|---|---|
| S106 | 14 | `System.out` in CLI commands: `FnTestCommand` ×9, `InvokeCommand` ×1, `EnqueueCommand` ×1, `FnListCommand` ×1, `FnGetCommand` ×1, `ExecGetCommand` ×1 | **Decision (user): targeted suppression.** `@SuppressWarnings("java:S106")` on the command classes with a comment explaining that stdout IS the CLI product — the logger is wrong for user-facing CLI output. |
| S125 | 1 | `InvocationService:143` commented-out lines | Remove the commented-out code. |
| S1118 | 1 | `VertxRuntimeHints:38` implicit public constructor | Add a private constructor (utility class). |
| S6833 | 1 | `BuildMetadataController:9` `@Controller` + `@ResponseBody` | Replace with `@RestController`, remove `@ResponseBody`. |
| S6813 | 1 | `RuntimeConfigApplier:29` field injection | Convert to constructor injection. |
| S2445 | 1 | `ExecutionCompletionHandler:168` — `synchronized (record)` on a method parameter | Delicate case: the parameter is the per-execution lock object (serializes completion per record). Fix together with the S6213 rename in the same file (rename to `executionRecord` first, then the synchronized still flags). Decide in the plan: a dedicated non-null monitor for the synchronized block with a documented comment, or suppression with justification if a monitor would change locking semantics. |
| S3358 | 1 | `ReactiveInvocationCoordinator:156` nested ternary | Extract the nested ternary into an independent statement. |

**Total check (per rule):** S6213 93 + S5778 37 + S2925 23 + S106 14 + S1117 10 + S5976 4 + S8786 3 + S6885 3 + S6355 3 + S1172 3 + S112 3 + S108 3 + S1068 3 + S6863 2 + S6829 2 + S5738 2 + S1854 2 + S1141 2 + S6833 1 + S6813 1 + S1871 1 + S3358 1 + S2446 1 + S2445 1 + S125 1 + S1118 1 = 220 ✓

## Global Constraints

- **Behavior-preserving:** no semantic change to any production or test behavior. Renames and refactors must compile and pass the full suite.
- **No `@SuppressWarnings`** except the three documented cases (S106 CLI output, S1172 `main(String[] args)`, S5738 deprecated-without-replacement if needed). Every suppression carries a comment explaining why it is not a real defect.
- **New findings introduced by fixes are in scope** — if a fix creates a new MAJOR+, it must be resolved before the task is done.
- **No Co-Authored-By trailers** in any commit.
- **Per-task gate:** `./gradlew test --no-parallel` green (all modules — shared SDK/common signature changes must not break other modules' tests).
- **GitNexus mandates:** run impact analysis before editing a symbol; `detect_changes` before committing. Remember: the GitNexus index is worktree-blind and resolves shared names to test doubles — use `file_path` disambiguation or grep fallback.

## Verification

1. Fresh incremental analysis on the running `sonar-sonata` server (`nanofaas-java` project) after the branch is complete.
2. Target: **0 open MAJOR** (404 → 182 open: MINOR + INFO only).
3. No new findings: per-rule/per-file diff against the baseline dump `/tmp/sonar-java-issues.json` (451 pre-#164 issues — MAJOR set is identical post-#164: 220).
4. Full suite green on merged main before push.
5. Then: merge to main (fast-forward), push, close issue #165 with a resolution table comment.

## Out of Scope

- Issue #166 (182 MINOR + 2 INFO) — separate effort.
- Python (8) and Rust (4) findings from the sonar.sh run — not part of this Java-only issue.
- The `sonar-sonata` container — leave running, do not delete.
- No CI integration for SonarQube (user constraint from #164: on-demand local script only).

## Execution Plan

Single implementation plan (user decision), 1 task per rule (~16 tasks), SDD in a worktree, task reviewers, final whole-branch review, then finish: merge → push → close #165.
