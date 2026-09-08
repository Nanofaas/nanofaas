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

## P01 — Binding lifecycle contract (this entry)

**ID:** P01 — write the ADR, state/event table and resource-ownership matrix.

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`.
- HEAD at the time of this entry (BASE for P01): `61d72e73528db62cf8ca465c6a037981d7ec13b0`
  (P00 commit).
- Documentation-only task: no production source, `openapi/core.yaml`, or test harness modified.

**Files changed**

- `docs/architecture/0001-execution-lifecycle-contract.md` (new) — the ADR.
- `docs/experiments/lifecycle-memory-2026-09/STATO.md` (this file, appended).

**What was verified in code/spec**

- Execution states and their transitions: `ExecutionRecord` (QUEUED/RUNNING/SUCCESS/ERROR/TIMEOUT;
  terminal final; `resetForRetry` allows RUNNING→QUEUED), `ExecutionState`.
- Terminal transition order: `ExecutionStore.settle` publishes outcome → invalidates live record →
  notifies terminal listeners (R2 window named); terminal listeners are `IdempotencyStore.markTerminal`
  (from `InvocationExecutionFactory`) and `ExecutionCompletionHandler.recordTerminalConclusionOnce`.
- Key lifecycle and public scope: `IdempotencyStore` (`pending`/`published`/`terminal`, `acquireOrGet`,
  `claimIfMatches`, `publishClaim`, `abandonClaim`, `markTerminal`), key composed as
  `functionName + ":" + key`. `openapi/core.yaml` documents `Idempotency-Key` (per-function,
  retained `ttl` after completion, `410 Gone` on payload eviction), the stable terminal states
  (`success`/`error`/`timeout`), and the `408` waiter-timeout description.
- Waiter-timeout path: `ReactiveInvocationCoordinator.invoke` `.timeout(...)` → `markTimeout()` on the
  shared record (the R3 defect); `suppressCancel=true` protects the shared future only.
- Completion/retry/offload/slot paths: `ExecutionCompletionHandler` (`completeUnderLock`,
  `handleRetry`, `releaseDispatchSlotOnce`, `handleAdministrativeExpiry`, `completeOffloadedExecution`,
  `failOffloadedExecution`), `InvocationExecutionFactory` (`createOrReuseExecution`, replay/gone
  branches, `abandonAdmission`/`publishAdmission`), `InvocationEnqueueSupport.admitIfNew`.
- Slot acquisition timing: `Scheduler.acquireNext`/`SyncScheduler.tickOnceInternal` acquire at
  dispatch; the direct no-queue path acquires none but releases by name (R5). `FunctionCapacityRegistry`
  (name-keyed release, reactivation on delete-then-recreate) and `ReplicaStatusSnapshot`
  (generation guard, stale-while-revalidate, no read-error→0 replicas) confirmed for I7/I9.
- Function removal/re-register: `FunctionService.remove`/`register`, queue drain
  (`SyncQueueService.removeFunctionState` → `FUNCTION_REMOVED`), `QueueManager.remove`. Removal does
  not clear `IdempotencyStore`/`ExecutionStore.outcomes`, so key retention survives remove/re-register.
- R3 red test: `R3WaiterTimeoutSharedOutcomeRegressionTest` asserts short-waiter timeout + backend
  success ⇒ long waiter and replay both observe `success`; the ADR matches that branch.

**Documented incompatibility (the one deliberate API decision)**

- R3: per-waiter timeouts. Today a short waiter stamps the SHARED record `TIMEOUT` (documented in
  `openapi/core.yaml`). The ADR resolves this to I1: a waiter timeout concludes only that waiter's
  wait; the shared execution continues and its real result is what replay/polling observe. The
  waiter-timeout HTTP format/status (`408`, `timeout` status) is kept; the caller budget, attempt
  deadline, retry policy and max execution lifetime stay separate. The implementation change is
  P05; the ADR records the decision now so P05 is not a silent state-machine change.

**Impact**

- Documentation-only. No symbol edited, so no GitNexus impact/edit gate applies; context was used
  to confirm callers/transitions rather than grep alone.

**Next step**

- P02 — conservative, bounded outcome weighing (I6), then P03 (atomic key budget), P04 (single
  terminal owner / dedup), P05 (activate the R3 waiter-timeout behavior + offload finalization).

## P02 — Conservative, bounded outcome weighing (this entry)

**ID:** P02 — make the outcome byte weigher conservative and bounded (finding R1).

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`.
- HEAD at the time of this entry (BASE for P02): `c9f0cc46` (P01 commit).

**Files changed**

- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/OutcomeWeigher.java`
  — rewritten: a weighing verdict that distinguishes *cacheable* (known weight) from *not cacheable*;
  bounded depth/width/identity traversal with cycle/shared-reference and opaque-object detection;
  saturating arithmetic; a `freeze` pass that deep-copies mutable containers into unmodifiable,
  unshared structures; non-Latin-1 strings priced at 2 bytes/char.
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java`
  — `settle` now freezes + weighs before inserting and declines to retain an outcome that is not
  cacheable or whose weight alone exceeds the byte budget; the terminal notification still runs
  either way (the key's tombstone is what keeps replay from re-invoking). Caffeine weigher bound to
  `OutcomeWeigher.weightForCache`; `maximumOutcomeBytes` kept as a field.
- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/ExecutionStoreProperties.java`
  — javadoc corrected: `maxOutcomes` derives the default byte budget only; there is no separate
  count cap. `COMPACT_OUTCOME_BYTES` doc updated.
- `platform/control-plane/src/main/resources/application.yml` — `max-outcomes`/`max-outcome-bytes`
  comments corrected to the derived-budget wording.
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/OutcomeWeigherTest.java`
  (new) — unit tests for depth/width/Unicode/cycle/shared/opaque/freeze-mutation/saturating/clamp.
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStoreEvictionTest.java`
  — added `aNotCacheableOutcomeIsDroppedButTheTerminalTransitionStillRuns`.
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/OutcomeWeightBudgetTest.java`
  — the old `aDeeplyNestedPayloadCostsNoMoreThanTheTraversalBound` (which asserted the R1 defect as
  desired behavior) is replaced by `aPayloadBeyondTheTraversalBoundIsDeclinedNotPricedAtZero` plus
  `aPayloadWithinTheTraversalBoundIsRetained`.
- `docs/experiments/lifecycle-memory-2026-09/STATO.md` (this file, appended).

**Test commands and outcomes**

R1 regression, RED on baseline (P00 established `expected: 0 but was: 31`; re-verified by stashing
the P02 production change):

```bash
./gradlew :control-plane:test --tests '*R1OutcomeByteBudgetRegressionTest'
# RED on baseline: 1 failed — AssertionFailedError at R1OutcomeByteBudgetRegressionTest.java:98
# GREEN after P02: BUILD SUCCESSFUL
```

New weigher unit tests and the surrounding store tests:

```bash
./gradlew :control-plane:test \
  --tests '*OutcomeWeigherTest' \
  --tests '*ExecutionStoreEvictionTest' \
  --tests '*OutcomeWeightBudgetTest' \
  --tests '*R1OutcomeByteBudgetRegressionTest'
# 33 tests completed, 0 failed
```

Full control-plane suite (run once before commit):

```bash
./gradlew :control-plane:test
# 557 tests completed, 7 failed, 3 skipped — the 7 failures are the still-red P00 regressions
# for R2/R3/R4/R7/R8 and direct admission (owned by P03–P10), none new from P02. R1 is green.
```

**Impact**

- `node .gitnexus/run.cjs impact "OutcomeWeigher" --direction upstream --repo .` → `UNKNOWN` /
  0 callers resolved (lambda reference not indexed); confirmed by text search: the only caller is
  `ExecutionStore` (Caffeine weigher) plus the R1 test javadoc.
- `node .gitnexus/run.cjs impact "ExecutionStore" --direction upstream --repo .` → ambiguous across
  11 symbols; the platform path resolves to MEDIUM risk / 34 impacted (`HIGH` among the candidates).
  `settle` callers (text search): `ExecutionCompletionHandler` (4 sites), `SyncQueueService` (2),
  `AsyncQueueConfiguration` (1); `outcomeOf` read by `InvocationService` and `InvocationExecutionFactory`.
- `node .gitnexus/run.cjs detect-changes --scope all --repo .` → 8 files / 22 symbols, risk `high`
  (ExecutionStore + OutcomeWeigher as expected; the listing also includes the pre-existing
  overload-path `STATO.md`/`run-queue-2.out` changes because the index is 3 commits behind HEAD —
  those files are not part of this change and are not committed).

**Measures / observations**

- The 30 × 1 MiB deep case and the 256-nulls + 1 MiB wide case are now declined (`size() == 0` under
  an 11,600-byte budget) instead of being retained at ~176/~112 bytes; the skipped subtrees are no
  longer priced at zero.
- The weigher still walks each outcome once at insertion and never re-serializes it; the ordinary
  compact-string path is unchanged (`FIXED_OVERHEAD_BYTES` and per-char Latin-1 pricing preserved, so
  `maxOutcomes` compact outcomes still fit the derived budget).
- The weight is an estimate, not a measurement: the docs explicitly avoid promising an exact heap bound.

**Next step**

- P03 — atomic idempotency-key budget, then P04 (single terminal owner / dedup; closes the R2 window
  the declined payload leaves), P05 (activate R3 waiter-timeout + offload finalization).

## P03 — Atomic idempotency-key budget (this entry)

**ID:** P03 — make `maxKeys` an atomic admission reservation, not a check-then-insert (finding R7).

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`.
- HEAD at the time of this entry (BASE for P03): `7828641b` (P02 commit).

**Files changed**

- `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyStore.java`
  — the budget is now an atomic reservation. `acquireOrGet` reserves a slot with a CAS on an
  `AtomicLong` (`occupied`) before inserting a new binding, instead of `size() >= maxKeys` then
  `putIfAbsent`. A claim that loses the `putIfAbsent` race returns its reservation and re-reads.
  Every entry owns exactly one slot, tied to the composed key, released exactly once: the Caffeine
  removal listener releases on `EXPIRED` only (its notifications for explicit remove/replace are
  buffered, not synchronous), while `abandonClaim` and `clear()` release in code. `put` (insertion
  helper) reserves for a new key and throws `IllegalStateException` at cap. Added
  `Scheduler.systemScheduler()` so expired entries evict (and release their slot) without traffic.
  New unlabeled metrics: `idempotency_keys_held` now reads the reservation counter (no `cleanUp()`
  on the scrape path) and a new `idempotency_key_budget_rejections` FunctionCounter.
- `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyKeyBudgetTest.java`
  (new) — 7 tests: same-key concurrent claims → one owner; concurrent abandon+expiry never make the
  quota negative and free slots for new claims; replay at saturation; replace/reclaim of one
  association never double-consumes; abandon releases once and the slot is reusable; shutdown
  releases every slot; refusals are counted unlabeled.
- `docs/experiments/lifecycle-memory-2026-09/STATO.md` (this file, appended).

**Test commands and outcomes**

R7 regression, RED on baseline (P00 established `claimed 16 (expected 1)` with `maxKeys=1`):

```bash
./gradlew :control-plane:test --tests '*R7KeyBudgetAtomicityRegressionTest'
# RED on baseline: 1 failed — AssertionFailedError (16 distinct claims admitted, expected 1)
# GREEN after P03: BUILD SUCCESSFUL
```

New budget tests plus the surrounding store/lifetime tests:

```bash
./gradlew :control-plane:test \
  --tests '*R7KeyBudgetAtomicityRegressionTest' \
  --tests '*IdempotencyKeyBudgetTest' \
  --tests '*IdempotencyStoreTest' \
  --tests '*IdempotencyKeyLifetimeTest'
# 27 tests completed, 0 failed
```

Full control-plane suite (run once before commit):

```bash
./gradlew :control-plane:test --no-parallel
# 565 tests completed, 6 failed, 3 skipped — the 6 failures are the still-red P00 regressions
# for R2/R3/R4/R8 (x2) and direct admission (owned by P04–P10), none new from P03. R7 is green.
```

**Impact**

- `node .gitnexus/run.cjs impact "IdempotencyStore" --direction upstream --repo .` → ambiguous (13
  symbols, including the decompiled staging snapshot); the platform path resolves to CRITICAL
  (`riskSharedAxes: LOW`), 23 impacted, direct callers `InvocationService` (constructor) and
  `InvocationExecutionFactory` (`ExecutionLookup`, `createClaimedRecord`). Confirmed by text search:
  the only production callers are `InvocationService` and `InvocationExecutionFactory`; the specific
  methods changed (`acquireOrGet`, `claimIfMatches`, `publishClaim`, `abandonClaim`, `markTerminal`,
  `put`) are called only by `InvocationExecutionFactory` and tests.
- `node .gitnexus/run.cjs impact "InvocationExecutionFactory" --direction upstream --repo .` →
  CRITICAL (`riskSharedAxes: MEDIUM`), 13 impacted, direct `InvocationService` constructor.
  `InvocationExecutionFactory` itself was NOT modified by P03 (the budget change is internal to
  `IdempotencyStore`), so the CRITICAL is the store's own, not a new edit risk.
- `node .gitnexus/run.cjs detect-changes --scope all --repo .` → 3 files / 27 symbols, risk `medium`;
  affected flows `acquireOrGet`, `claimIfMatches`, `publishClaim`. The listing includes the
  pre-existing overload-path `STATO.md` (not committed) because the index lags HEAD.

**Measures / observations**

- The Caffeine removal listener is NOT synchronous for explicit removals: `asMap().remove(key,value)`
  and `invalidateAll()` buffer their `EXPLICIT` notification and flush it later, while `EXPIRED` is
  flushed synchronously by `cleanUp()`. Verified with a standalone probe (Caffeine 3.2.3). The design
  therefore releases in code for `abandonClaim`/`clear()` and lets the listener own only `EXPIRED`,
  so no slot is released twice and none is missed.
- `idempotency_keys_held` now reads the reservation counter instead of `size()` (which called
  `cleanUp()` on the scrape path); `idempotency_key_budget_rejections` is a plain, unlabeled counter.
- The one-off flaky full-suite run (a custom-ticker + scheduler maintenance race in the expiry
  observation, not a reservation bug) was made deterministic by draining expired entries with a
  bounded maintenance poll; no `sleep` is used as proof of ordering.

**Next step**

- P04 — single terminal owner / dedup (closes the R2 window the declined payload leaves), then P05
  (activate R3 waiter-timeout + offload finalization).
