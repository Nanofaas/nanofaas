# Lifecycle, memory and modularity campaign — execution record

Campaign plan: `docs/plans/2026-09-08-control-plane-lifecycle-memory-and-modularity.md`.
Review under test: `docs/control-plane-pre-soak-review-2026-09-08.md`.
Diagnostic harness: `docs/experiments/pre-soak-review-2026-09-08/`.

## P00 — Evidence and regression tests (this entry)

**ID:** P00 — prepare evidence and reproducible regressions.

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`.
- HEAD at the time of this entry: `f8279cac839be940275542195216c0fdd9081a3f`.
- Reviewed revision (analyzed by the review): `1d9e2f5518c21641be2e791cca9a952ca4128d34` (`main`, v0.21.0).
- Historical-baseline reference kept for the soak comparison: `e35405ee` (`Record the v0.20.0 performance release`).
- Working tree at start: `docs/experiments/overload-path-2026-09/STATO.md` and
  `docs/experiments/overload-path-2026-09/run-queue-2.out` already modified (left untouched),
  plus unrelated untracked `.claude/skills/gitnexus-*/` directories (left untouched).
- No production source under `platform/`, `sdks/`, `openapi/`, or `deploy/` was modified.

**Environment facts**

- JVM: OpenJDK `25.0.4` (`25.0.4+7-1-24.04-Ubuntu`), 64-Bit Server VM.
- Architecture: Linux `aarch64`.
- Gradle daemon JVM args: `-Xmx1g` (`org.gradle.jvmargs`), `GRADLE_OPTS`/`JAVA_OPTS` empty.
- `javaVersion=25` toolchain; project version `0.21.0`.
- Default selected control-plane modules (module descriptors, `defaultEnabled=true`):
  `async-queue`, `autoscaler`, `build-metadata`, `concurrency-control`, `k8s-deployment-provider`,
  `offload`, `runtime-config`. `sync-queue` and `container-deployment-provider` are
  `defaultEnabled=false`.
- Control-plane defaults (from `application.yml` / `ExecutionStoreProperties`): timeout 30 000 ms,
  concurrency 4, queue size 100, max retries 3; outcome retention TTL 5 min, sync TTL 30 s,
  max lifetime 30 min; `max-outcomes` 100 000 with byte budget derived as
  `max-outcomes x 116 B` unless `max-outcome-bytes` is explicit; `max-keys` 100 000.

**Existing-suite baseline outcome**

`./gradlew test --no-parallel --console=plain`

- Result: `BUILD SUCCESSFUL` in 2 m 19 s — 190 actionable tasks, 77 executed, 113 up-to-date.

**What P00 added (test-only)**

New regression tests, one per reproduced finding, placed in the owning component's test
source tree. Each asserts the DESIRED (correct) behavior and is therefore RED on this baseline.

| Finding | New test (file) | RED evidence on baseline | Greened by |
|---|---|---|---|
| R1 outcome byte budget ignores deep/wide payloads | `control-plane .../execution/R1OutcomeByteBudgetRegressionTest` | `expected: 0 but was: 31` | P02 |
| R2 eviction reopens the key mid terminal transition | `control-plane .../service/R2ArchiveEvictionReplayRegressionTest` | replay `isNew` `true` (expected `false`) | P04 |
| R3 short waiter overwrites shared outcome | `control-plane .../service/R3WaiterTimeoutSharedOutcomeRegressionTest` | replay status `"timeout"` (expected `"success"`) | P01/P05 |
| R4 offload completion after waiter timeout leaves live record | `control-plane .../service/R4OffloadCompletionAfterWaiterTimeoutRegressionTest` | live `1` (expected `0`); future pending | P04/P05 |
| R5 direct completion releases an unowned slot | `sync-queue .../R5DirectCompletionUnownedSlotRegressionTest` | in-flight `0` (expected `1`) | P06 |
| R6 failed deprovision loses proxy ownership/state | `container-deployment-provider .../R6DeprovisionFailureOwnershipRegressionTest` | ownership lost (`proxyClosed || tracked` was `false`) | P08 |
| R7 `maxKeys` check is not atomic | `control-plane .../execution/R7KeyBudgetAtomicityRegressionTest` | claimed `16` (expected `1`, `maxKeys=1`) | P03 |
| R8 metric names and replica entries accumulate after removal | `control-plane .../service/R8HistoryCleanupRegressionTest` | removed names `1000` (expected `0`); replica entries `1000` (expected `0`) | P09 (metrics), P10 (snapshot) |
| Direct core admission bypasses concurrency without a queue | `control-plane .../service/DirectAdmissionWithoutQueueBoundedRegressionTest` | live `100` (expected `<= 1`) | P06/P07 |

The two R8 methods are kept separate so P09 (metrics) and P10 (replica snapshot) can be
greened independently; until P10 the snapshot assertion is an intentionally explicit red test,
as the plan directs.

**Verification matrix additions (paths)**

| Profile | What is exercised | Result on baseline |
|---|---|---|
| No queue | Direct core admission bound (R-finding above) | RED |
| Async queue `:invoke` SYNC | Enqueues and waits for the dispatch result | GREEN |
| Async queue `:enqueue` ASYNC | Answers early; completion follows the later dispatch | GREEN |
| Async queue both, concurrently | Same function/queue/capacity/scheduler; single slot shared; drain to zero | GREEN |
| Async queue saturation | Queue-full refusal of a new `:enqueue` while queued SYNC work still completes | GREEN |
| Async queue retry | SYNC `:invoke` waits across a retry re-enqueued through the same queue | GREEN |
| Sync queue enabled/disabled, offload, LOCAL/EXTERNAL/DEPLOYMENT, remove/re-register | Represented by R2–R6 tests and existing suites | (see RED rows above) |

The async-queue matrix is covered by
`async-queue .../AsyncQueueInvokeEnqueueContractRegressionTest` (5 methods). These are
contract tests for the shared per-function queue/capacity/scheduler, NOT reproductions of a
single R finding: they currently pass and must keep passing as P01–P09 change the lifecycle.
They do not collapse `:invoke` and `:enqueue` into one path.

**Test commands and outcomes**

Existing suite (baseline, before adding the new tests):

```bash
./gradlew test --no-parallel --console=plain        # BUILD SUCCESSFUL in 2m19s
```

New red regressions (run separately from the existing suite; all fail for the expected
assertion, none for a compile error or missing dependency):

```bash
./gradlew :control-plane:test \
  --tests '*R1OutcomeByteBudgetRegressionTest' \
  --tests '*R7KeyBudgetAtomicityRegressionTest' \
  --tests '*R2ArchiveEvictionReplayRegressionTest' \
  --tests '*R3WaiterTimeoutSharedOutcomeRegressionTest' \
  --tests '*R4OffloadCompletionAfterWaiterTimeoutRegressionTest' \
  --tests '*R8HistoryCleanupRegressionTest' \
  --tests '*DirectAdmissionWithoutQueueBoundedRegressionTest'
# 8 tests completed, 8 failed (all AssertionFailedError on the desired behavior)

./gradlew :control-plane-modules:sync-queue:test \
  --tests '*R5DirectCompletionUnownedSlotRegressionTest'       # 1 failed (expected 1, was 0)

./gradlew :control-plane-modules:container-deployment-provider:test \
  --tests '*R6DeprovisionFailureOwnershipRegressionTest'       # 1 failed (ownership lost)
```

New green matrix contract tests:

```bash
./gradlew :control-plane-modules:async-queue:test \
  --tests '*AsyncQueueInvokeEnqueueContractRegressionTest'     # 5 tests passed
```

**Files changed by P00** (all new; nothing modified)

- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/R1OutcomeByteBudgetRegressionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/R7KeyBudgetAtomicityRegressionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/R2ArchiveEvictionReplayRegressionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/R3WaiterTimeoutSharedOutcomeRegressionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/R4OffloadCompletionAfterWaiterTimeoutRegressionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/R8HistoryCleanupRegressionTest.java`
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/DirectAdmissionWithoutQueueBoundedRegressionTest.java`
- `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/R5DirectCompletionUnownedSlotRegressionTest.java`
- `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/R6DeprovisionFailureOwnershipRegressionTest.java`
- `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueInvokeEnqueueContractRegressionTest.java`
- `docs/experiments/lifecycle-memory-2026-09/STATO.md` (this file)

**Impact**

- Test-only change. No production source under `platform/`, `sdks/`, `openapi/`, or `deploy/`
  was edited. `gitnexus detect-changes --scope all` reported no production-symbol change
  (2 changed files / 1 doc "symbol", the pre-existing overload-path `STATO.md`; risk low) —
  the expected clean result for new tests only.
- The R8 tests read private fields (`Metrics.removedFunctions`, `ReplicaStatusSnapshot.entries`)
  by reflection. Later tasks that remove or rename those fields (P09/P10) must update the tests.

**Measures / observations**

- RED tests are time-bounded: they use latches/barriers, controlled dispatch futures and
  maximum wait durations; no `sleep` is used as proof of ordering.
- R3/R4 confirm the review's contract note: R3 is an intentional API-semantics inconsistency
  (OpenAPI currently documents the stable `TIMEOUT`), so its resolution needs the deliberate
  decision recorded in P01/P05, not a silent state-machine change.

**Documented incompatibilities**

- None introduced. P00 only adds tests and this record. The two pre-existing modified
  experiment files and the untracked `.claude/skills/gitnexus-*/` directories are untouched.

**Next step**

- P01 — write the contract/state-machine/ADR and the resource-ownership matrix; make the R3
  semantic change explicit, then P05 activates the new waiter-timeout behavior tests.
