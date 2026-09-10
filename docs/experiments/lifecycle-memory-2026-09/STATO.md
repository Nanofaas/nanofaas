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

## P04 — Single owner of the terminal transition and the dedup guarantee

**ID:** P04 — introduce `ExecutionLifecycle` as the single owner of an invocation's terminal
transition and close the R2 dedup gap.

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`. BASE (P03): `8320ef18`; this entry's commit is recorded
  below once committed.

**What P04 changed**

- `ExecutionLifecycle.java` (new, `execution` package) — the single owner of the terminal
  transition. `settle(record)` runs, in order: (1) dedup protection — `markTerminal` under the
  record monitor, so the key is non-reclaimable before the last live reference can disappear and so
  it is serialized against the admission path's publish; (2) archive (optional, weight-bounded); (3)
  live removal (`inFlight.invalidate`); (4) best-effort observer notification. No global lock: the
  record is the monitor, and no listener/dispatcher/external code runs while it is held.
- `ExecutionStore.java` — `settle` becomes the public adapter the queue modules still call; it
  delegates to the attached `ExecutionLifecycle`, and fails fast (`IllegalStateException`) when a
  keyed record is settled without one rather than silently dropping `markTerminal` (a keyless record
  still archives via the package-private `archiveAndRemove`+`notifyTerminal` half). Added
  `archiveAndRemove`, `notifyTerminal` and `attachLifecycle`.
- `IdempotencyStore.java` — added the explicit `reclaimable` (abandoned) binding state and
  `markReclaimable`; `claimIfMatches` now re-claims ONLY an explicitly-abandoned binding (identity
  matched), never a still-`published` one — the absence of a record/outcome is no longer proof that
  a published claim was abandoned. `markTerminal` skips `reclaimable` bindings (an abandoned
  execution must not be tombstoned).
- `InvocationExecutionFactory.java` — removed the `onTerminal` markTerminal listener (the owner does
  it as a first-class step); constructs and attaches the `ExecutionLifecycle`; `publishAdmission`
  runs `publishClaim` + the terminal re-check under the record monitor (closing the
  completion-before-publication ordering); `abandonAdmission` marks a published binding reclaimable;
  the replay loop parks on a still-published binding instead of re-claiming it.
- Tests updated for the new contract: `IdempotencyKeyBudgetTest.replacingAndReclaiming…` and the two
  `InvocationServiceDispatchTest.invokeAsync_staleIdempotencyMapping…` now mark the stale binding
  reclaimable explicitly; `IdempotentRetentionContractTest.aLateSettle…` abandons via
  `abandonAdmission()`. New `ExecutionLifecycleTerminalTransitionTest` (7 tests) covers weight and
  TTL eviction during completion, delayed key publish, concurrent replay, a throwing terminal
  listener, double completion and completion-against-expiry, counting real dispatcher starts.

**Test commands and outcomes**

R2 regression, RED on baseline (P00 established `replay isNew true (expected false)`), GREEN now:

```bash
./gradlew :control-plane:test --tests '*R2ArchiveEvictionReplayRegressionTest'
# GREEN: BUILD SUCCESSFUL (unchanged test — the factory now attaches the owner)
```

New transition suite plus the surrounding store/key/lifetime tests:

```bash
./gradlew :control-plane:test \
  --tests '*ExecutionLifecycleTerminalTransitionTest' \
  --tests '*IdempotencyKeyBudgetTest' \
  --tests '*IdempotencyKeyLifetimeTest' \
  --tests '*IdempotentRetentionContractTest' \
  --tests '*InvocationServiceDispatchTest.invokeAsync_staleIdempotencyMapping*'
# BUILD SUCCESSFUL
```

Full control-plane suite (run once before commit):

```bash
./gradlew :control-plane:test --no-parallel
# 572 tests completed, 5 failed, 3 skipped — the 5 failures are the still-red P00 regressions
# for R3/R4/R8 (x2) and direct admission (owned by P05/P06/P07/P09/P10). R2 is green; none new.
```

Queue/provider modules still green except their own P00 reds: async-queue green; sync-queue 1
failure = R5 (P06); container-deployment-provider 1 failure = R6 (P08); offload and
concurrency-control green.

**Impact**

- `node .gitnexus/run.cjs impact "ExecutionStore" --direction upstream --repo . --file
  platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java`
  → CRITICAL (`riskSharedAxes: MEDIUM`), 34 impacted, 8 direct, 8 processes / 3 modules; main flows
  `invokeSyncReactive`, `coreAdmission`, `disabledSyncSlot`, `offloadRetention`.
- `node .gitnexus/run.cjs impact "InvocationExecutionFactory" --direction upstream --repo .` →
  CRITICAL, 13 impacted, 6 direct, 5 processes.
- `node .gitnexus/run.cjs impact "ExecutionCompletionHandler" --direction upstream --repo .` →
  MEDIUM (18 impacted, 5 direct) — NOT modified by P04 (its `executionStore.settle` calls route
  through the adapter unchanged).
- `node .gitnexus/run.cjs impact "ExecutionRecord" --direction upstream --repo .` → UNKNOWN ("no
  callers resolved"; index 5 commits behind). Confirmed by text search: NOT modified by P04.
- `node .gitnexus/run.cjs detect-changes --scope all --repo .` → 7 files / 23 symbols, risk `high`;
  affected flows `createOrReuseExecution`, `claimIfMatches`, `markTerminal`, `settle`. The listing
  includes the pre-existing overload-path `STATO.md` (not committed) because the index lags HEAD.

**Measures / observations**

- The invariant is now ordered, not listener-based: `markTerminal` runs first, under the record
  monitor, so no interleaving of publish/evict/settle can leave a still-valid published key without
  an execution, outcome or tombstone.
- Re-claiming requires explicit state: `claimIfMatches` re-claims only `markReclaimable` bindings.
  The two `staleIdempotencyMapping` tests and the budget test were updated to model the explicit
  abandon instead of inferring it from `executions.remove(...)`.
- Administrative expiry co-expiry note (pre-existing, out of P04 scope): the published key and the
  inFlight record share `maxLifetime`, so an expiry-driven `markTerminal` races the key's own expiry
  and usually no-ops; the execution never concluded, so a fresh execution after the retention is
  correct. Recorded for P06.
- The custom-ticker + `Scheduler.systemScheduler()` expiry observation is flaky in the full suite
  (also seen in P03); the completion-against-expiry test uses a real ticker and a short
  `maxLifetime` to stay deterministic, and every concurrent test is latch/barrier time-bounded.

**Next step**

- P05 — activate the R3 waiter-timeout decision and offload finalization (R4), which now sit on a
  single terminal owner.

## P05 — Per-waiter timeouts and unconditional offload finalization

**ID:** P05 — activate the R3 waiter-timeout decision (per-waiter timeouts) and make offload
finalization unconditional (finding R4).

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`. BASE (P04): `c1553077` ("P04 review fix: fail fast
  when settling a keyed record without an owner"). This entry's commit is the P05 commit.

**What P05 changed**

- `ReactiveInvocationCoordinator.invoke` — the waiter's `.timeout(...)` budget no longer calls
  `executionRecord.markTimeout()`. A waiter timeout concludes only that waiter's wait (the
  documented 408/`timeout` response is unchanged); the shared record, key, store, lease, budget
  and counters are untouched, and the shared execution keeps running. The waiter timeout is still
  counted on `function_timeout_total` (`metrics.timeout`), never as a backend error.
- `ExecutionCompletionHandler.completeOffloadedExecution` / `failOffloadedExecution` — finalization
  is unconditional: the state mutation is guarded by a `concluded = !isTerminal(...)` flag, but the
  shared-future completion and the `settle` always run. An already-terminal record no longer makes
  this an early return that leaves a finished offload live in the store with a pending future; the
  already-definitive result prevails and `complete`/`completeExceptionally`/`settle` are idempotent
  no-ops on a concluded record.
- `ExecutionCompletionHandler.completeExecution` — removed the `lateResultForTerminalRecord` branch
  that answered the shared future with a late dispatch result after a timeout. Under the new
  contract every terminal marker concludes the shared future itself, so a late result must not
  overwrite the already-definitive answer; the slot is still released and the settle still runs.
- `AsyncQueueConfiguration.markFunctionRemoved` — the queue-side terminal early return no longer
  skips the `settle`; the state mutation is guarded, the settle is unconditional (idempotent).
- `openapi/core.yaml` — the `408` description, the `GET /v1/executions/{id}` 200 description and the
  `ExecutionStatus.status` `timeout` sentence now document the per-waiter semantics: a caller's wait
  timeout does not make the execution terminal; `timeout` records an execution-level deadline only.
- Tests: `InvocationServiceDispatchTest` (`invokeSync_`/`invokeSyncReactive_timeoutRemainsTerminal…`)
  now assert the real success reaches the replay; `ExecutionCompletionHandlerSlotReleaseTest`'s
  timeout test now asserts the already-concluded future is not overwritten; the expiry and timing
  tests' comments reflect execution-level (not waiter) timeouts. New
  `P05WaiterTimeoutSharedOutcomeTest` covers both waiter arrival orders, error, retry, offload
  failure, all-waiters-detached, and the execution-level (global) deadline.

**Test commands and outcomes**

R3/R4 anchors, RED on the P04 baseline, GREEN after P05 (unchanged tests):

```bash
./gradlew :control-plane:test \
  --tests '*R3WaiterTimeoutSharedOutcomeRegressionTest' \
  --tests '*R4OffloadCompletionAfterWaiterTimeoutRegressionTest'
# RED on baseline (P04): R3 replay "timeout" (expected "success"); R4 live=1/future pending.
# GREEN after P05: BUILD SUCCESSFUL
```

New per-waiter/offload contract tests:

```bash
./gradlew :control-plane:test --tests '*P05WaiterTimeoutSharedOutcomeTest'
# 6 tests completed, 0 failed
```

Full control-plane suite (once before commit):

```bash
./gradlew :control-plane:test --no-parallel
# 578 tests completed, 3 failed, 3 skipped — the 3 failures are the still-red P00 regressions
# R8 (x2, P09/P10) and direct admission (P06/P07). R3 and R4 are green; none new from P05.
```

Module suites:

```bash
./gradlew :control-plane-modules:offload:test :control-plane-modules:async-queue:test   # BUILD SUCCESSFUL
./gradlew :control-plane-modules:sync-queue:test    # 87 tests, 1 failed = R5 (P06), unchanged
./gradlew :control-plane-modules:concurrency-control:test   # BUILD SUCCESSFUL
```

**Impact**

- `node .gitnexus/run.cjs impact "ReactiveInvocationCoordinator" --direction upstream --repo .` →
  HIGH (`riskSharedAxes: MEDIUM`), 12 impacted, 5 direct, 4 processes (main/coreAdmission/
  offloadRetention/disabledSyncSlot).
- `node .gitnexus/run.cjs impact "ExecutionCompletionHandler" --direction upstream --repo .` →
  ambiguous (5 symbols); the platform path resolves to MEDIUM (18 impacted, 5 direct).
- `node .gitnexus/run.cjs impact "completeOffloadedExecution" --direction upstream --repo .` →
  CRITICAL (`riskSharedAxes: LOW`), 3 impacted, 1 direct (7 processes); `failOffloadedExecution`
  the same.
- `node .gitnexus/run.cjs detect-changes --scope all --repo .` → 10 files / 21 symbols, risk
  `critical`; affected flows `invoke` and the offload/dispatch completions. The listing includes
  the pre-existing overload-path `STATO.md` (not committed) because the index lags HEAD.

**Measures / observations**

- A waiter timeout now leaves the shared record non-terminal, so the long waiter and the replay both
  read the real backend answer; the R4 leak (a finished offload retained in `inFlight` until the
  30-minute `maxLifetime`) is gone without waiting for administrative expiry — the remote result
  settles immediately.
- The execution-level `TIMEOUT` terminal (the sync-queue `QUEUE_TIMEOUT` expiry) is unchanged: it
  still concludes the whole execution, completes the shared future with `QUEUE_TIMEOUT`, settles,
  and every observer/replay reads `timeout`; a late success cannot change it.
- Concurrency tests are latch/future/barrier time-bounded (max durations); no `sleep` is used as
  proof of ordering.

**Next step**

- P06 — attempt-scoped leases and dispatch cancellation (closes R5 and the administrative-expiry
  transport-handle gap).

## P06 — Attempt-scoped leases, mandatory capacity and local cancellation

**ID:** P06 — introduce attempt-scoped `DispatchLease`/`DispatchAttempt` with function-generation
identity, move the mutable capacity into the core, bound every attempt (with or without a queue
module), register the real cancellable transport handle, and make local cancellation real. Closes
review finding R5 and the "direct core admission" risk.

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`. BASE (P05): `879c03c4` ("P05: per-waiter timeouts and
  unconditional offload finalization (R3/R4)"). This entry's commit is the P06 commit.

**What P06 changed**

- New core capacity package `it.unimib.datai.nanofaas.controlplane.capacity`:
  - `DispatchLease` — an attempt-owned capacity lease carrying the function name, the generation
    identity and an idempotent `release()` (atomic). The lease is the only thing a completion path
    may release: there is no name-based release on the core dispatch path any more.
  - `DispatchAttempt` — `(executionId, attempt, lease)`; a path that never acquired local capacity
    (offload) carries no lease.
  - `FunctionCapacityState` / `FunctionCapacityRegistry` — moved from `workload-metrics`, now
    generation-aware. Each registration is a generation; `remove` retires it, re-registering while
    it drains creates a NEW generation, and an old lease releases only its own generation
    (invariant I4 / I7). The name-based methods (`tryAcquireSlot`, `releaseSlotAndGetHoldNanos`,
    `state`, `inFlight`, `setEffectiveConcurrency`, ...) remain as the temporary adapters the queue
    modules still use (their retirement is P20). The old `workload-metrics` classes are deleted; the
    governor's `WorkloadCapacityController` is now a one-line adapter bean in each queue module.
- `ExecutionRecord` — tracks the current attempt's `DispatchLease` (`attachDispatchLease` /
  `takeDispatchLease`, plus a `directAdmission` flag that survives the take so a second release
  path never falls through to a name release) and the raw cancellable transport handle
  (`attachDispatchHandle` / `takeDispatchHandle`); `wasDispatched()` (startedAt set) is the
  "this attempt acquired capacity" discriminator. `resetForRetry` clears all three.
- `ExecutionCompletionHandler` — injects the core `FunctionCapacityRegistry` (a handler built
  without one owns a private registry, so the no-queue profile is still bounded).
  `dispatch(task)` is the queue path (a scheduler already acquired name-based); new
  `dispatchDirect(task)` acquires the lease itself and throws `QueueFullException` (429) when there
  is no room; new `dispatchWithLease(task, lease)` is used by the core-only retry enqueuer. The raw
  transport future is published on the record BEFORE the completion callback (an expiry that wins
  the race can still cancel it), and the attempt's own deadline (`orTimeout(spec.timeoutMs)`) feeds
  the retry policy. `releaseAttemptCapacity` releases exactly what the attempt acquired: the lease
  for a direct admission, the name-based slot for a queue dispatch, nothing for work that never
  dispatched (this is what closes R5). `handleAdministrativeExpiry` now releases the capacity and
  cancels the raw handle.
- `ReactiveInvocationCoordinator` — the direct admission path calls `dispatchDirect`; the P05 dead
  `onErrorResume` branch no longer marks the record ERROR without concluding it — it routes the
  exceptional completion through `completeExecution` so the record settles (invariant I1/I3).
- `ExecutorBackedInvocationEnqueuer` (core-only retry) — each retry now acquires a capacity lease
  before submission and hands it to `dispatchWithLease`; no room returns `false` (retry exhausted)
  instead of dispatching unbounded work.
- `RegistryDefaultsConfiguration` — registers the single `@Bean FunctionCapacityRegistry`
  (`@ConditionalOnMissingBean`), shared by the direct path and both queue modules.
- Wiring: `SyncQueueConfiguration` / `AsyncQueueConfiguration` drop their own registry beans and
  provide `WorkloadCapacityController` adapters over the shared core registry; `SyncQueueService`,
  `SyncQueueInvocationEnqueuer`, `SyncQueueWorkloadMetricsSource`, `QueueManager`,
  `FunctionQueueState` import the core types.
- Tests: the R5 and direct-admission regressions go GREEN; a new
  `DispatchLifecycleAndCancellationTest` covers the exactly-one-of-100 bound, lease idempotency,
  generation fencing on retirement, double callback, administrative-expiry cancellation of a
  non-cooperative LOCAL future, and cancellation of a real HTTP call at the transport boundary
  (MockWebServer); several existing completion/retry tests now mark the attempt dispatched before
  completing, and the direct-path dispatch tests assert no name-based release.

**Test commands and outcomes**

R5 + direct admission anchors, RED on the P05 baseline, GREEN after P06 (unchanged tests):

```bash
./gradlew :control-plane:test --tests '*DirectAdmissionWithoutQueueBoundedRegressionTest'
# RED on baseline (P05): live 100 (expected <= 1). GREEN after P06: BUILD SUCCESSFUL.

./gradlew :control-plane-modules:sync-queue:test --tests '*R5DirectCompletionUnownedSlotRegressionTest'
# RED on baseline (P05): in-flight 0 (expected 1). GREEN after P06: BUILD SUCCESSFUL.
```

New lease / cancellation / retirement tests:

```bash
./gradlew :control-plane:test --tests '*DispatchLifecycleAndCancellationTest'   # 13 tests, 0 failed
./gradlew :control-plane:test --tests '*FunctionCapacityRegistryTest'          # BUILD SUCCESSFUL
```

Full suites (once before commit):

```bash
./gradlew :control-plane:test --no-parallel
# 599 tests completed, 2 failed, 3 skipped — the 2 failures are the still-red P00 regressions R8
# (x2, P09/P10). R5 and direct admission are green; none new from P06.

./gradlew :control-plane-modules:sync-queue:test :control-plane-modules:async-queue:test \
  :workload-metrics:test :control-plane-modules:concurrency-control:test          # BUILD SUCCESSFUL
./gradlew :control-plane-modules:autoscaler:test :control-plane-modules:offload:test \
  :control-plane-modules:k8s-deployment-provider:test :control-plane-modules:build-metadata:test \
  :control-plane-modules:runtime-config:test                                      # BUILD SUCCESSFUL
# container-deployment-provider still carries the pre-existing R6 red (P08), unchanged.
```

**Impact (GitNexus)**

- `node .gitnexus/run.cjs impact "FunctionCapacityRegistry" --direction upstream --repo .` →
  HIGH, 32 impacted (18 direct), 3 processes, 3 modules; boundary note: `WorkloadCapacityController`
  is an interface with 2 implementations, so interface dispatch is a lower bound.
- `node .gitnexus/run.cjs impact "InvocationEnqueuer" --direction upstream --repo .` →
  ambiguous (3 symbols); the platform path (disambiguated by `--file`) resolves to HIGH, 18
  impacted, 7 direct, 4 processes.
- `node .gitnexus/run.cjs impact "SyncQueueInvocationEnqueuer" --direction upstream --repo .` →
  LOW (3 impacted, 2 direct). `impact "QueueBackedEnqueuer"` → LOW (1 impacted, 1 direct).
- `node .gitnexus/run.cjs detect-changes --scope all --repo .` → 33 files / 82 symbols, risk
  `critical`; affected flows `invoke` and the dispatch/completion paths. The move of a HIGH-risk
  symbol plus the `ExecutionRecord`/`ExecutionCompletionHandler` changes account for the severity;
  the queue modules were migrated behind the same public name-based API so their behavior is
  unchanged.

**Measures / observations**

- No path bypasses the cap: the direct path (no queue), the queue schedulers, and the core-only
  retry enqueuer all acquire against the same generation-aware registry. At `concurrency=1`, 100
  concurrent direct calls admit exactly 1 and refuse 99 with the overload contract (429), no
  implicit unbounded queue.
- `accepted + released == acquired`, no negatives: every release is a lease the attempt owns (or a
  name-based slot the queue scheduler acquired), and a direct completion of work that never
  dispatched releases nothing (R5). Administrative expiry releases exactly once, then cancels the
  raw transport handle; the re-entrant completion from the cancel finds the capacity already
  released and does nothing.
- Cancellation is local and tested at the transport boundary: `cancel(true)` on the raw
  `Mono.toFuture()` disposes the HTTP subscription (asserted against a controlled MockWebServer),
  and a composed future is never relied on as proof. No promise is made that a remote or a
  non-cooperative LOCAL function stops.

**Next step**

- P07 — finite count/byte admission for live/queued payload bytes (invariant I5's byte half).

## 2026-09-09 — Correzioni della review P00–P06

**Revisione e perimetro.** Base verificata `c8d6dd028624e85ee284bd46842f1c1304d1e967`,
branch delle correzioni `fix/p00-p06-review`. L'implementazione parallela è stata fermata
prima dell'intervento. Questa voce corregge le conclusioni troppo ampie delle voci P01–P06
precedenti: i test originari erano verdi, ma dodici riproduzioni aggiuntive della review
fallivano. P07 e i task successivi non sono implementati da questo intervento.

**Correzioni eseguite.**

- **P01:** riallineato ADR 0001. La protezione della chiave precede pubblicazione dell'outcome
  e rimozione del live record; non è un listener best-effort. Una dispatch che lancia prima
  di restituire la future può avere già acquisito capacità e deve restituirla.
- **P02:** `OutcomeWeigher` ammette soltanto wrapper numerici immutabili di dimensione nota;
  tipi numerici opachi/mutabili sono esclusi dalla retention. Conta backing array, riferimenti,
  nodi dei contenitori, chiavi e wrapper della cache. Limite complessivo di 1.024 valori,
  oltre ai limiti per profondità e larghezza; le stringhe grandi si pesano senza scansione.
  La cache conserva il peso già calcolato, evitando la seconda traversata. Il limite in byte
  viene applicato durante il freeze, prima di clonare un array troppo grande. Un outcome
  oltre il peso rappresentabile da Caffeine viene rifiutato. Errori di traversata non
  interrompono il cleanup terminale.
- **P03:** eviction listener sincrono per la scadenza delle chiavi; `clear` restituisce solo
  le prenotazioni degli elementi effettivamente rimossi, senza azzerare il contatore mentre
  possono esistere nuove ammissioni. Una notifica scaduta non sottrae quota a una nuova chiave.
- **P04/P05:** il record seleziona la risposta terminale, inclusa l'eccezione offload, e il
  lifecycle ne pubblica la versione canonica. Un solo settler archivia/rimuove/notifica;
  metriche e osservatori non possono lasciare future pendenti o impedire il cleanup.
  Un completamento concorrente non può rispondere con un risultato diverso da quello archiviato.
- **P05:** il budget del singolo waiter non diventa la deadline dell'offload condiviso.
  Quest'ultimo usa `FunctionSpec.timeoutMs`; la sua subscription è registrata per la
  cancellazione amministrativa. Il test con tempo virtuale verifica anche la risposta
  tardiva al waiter lungo dopo il timeout di quello breve.
- **P06:** entrambi gli scheduler trasportano la lease acquisita nel task di dispatch.
  Rilascio, eccezioni e completamenti tardivi restano legati alla generazione acquisita;
  l'adattatore legacy per nome non può consumare slot posseduti da lease. Il core registra
  capacità e aggiornamenti anche senza moduli queue. Task con configurazione obsoleta non
  sovrascrivono configurazione gestita o limite del governor.
- **P06:** la cancellazione precedente alla pubblicazione dell'handle viene ricordata;
  DEPLOYMENT la inoltra al subscriber HTTP senza cancellare il wake-up condiviso. I timeout
  di tentativo cancellano il trasporto remoto. Il lavoro LOCAL non cooperativo mantiene la
  capacità fino alla fine reale, anche dopo la scadenza amministrativa. Il rilascio della
  capacità precede la pubblicazione del risultato del tentativo e l'ammissione del retry.

**File interessati.** Store e lifecycle in `platform/control-plane/.../execution`, registro
in `.../capacity`, listener in `.../registry/RegistryDefaultsConfiguration`, task e handler/
coordinator/enqueuer nel core; scheduler e provider delle due code; test di regressione e
fixture degli scheduler. Aggiornati `docs/control-plane.md`, ADR e descrizione OpenAPI del
408. Nessuna nuova proprietà o modifica Helm necessaria.

**Evidenza red/green e test.**

- La review sulla copia isolata di `c8d6dd0` aveva 12 test aggiuntivi rossi per le cause
  attese, senza errori di compilazione. Esempi: BigInteger di circa 1 MiB pesato 114 byte;
  lista di 256 null pesata 114 byte; quota 1 con 2 chiavi; future pendente dopo errore
  nelle metriche; risposta offload `late` contro outcome `first`; capacità LOCAL 0 con
  un worker ancora attivo; subscriber DEPLOYMENT non cancellato.
- Le riproduzioni sono ora versionate in `ReviewAccountingRegressionTest`,
  `ReviewLifecycleGateTest` e `ReviewOffloadWaiterBudgetTest`. Il test della pubblicazione
  tardiva dell'handle usa EXTERNAL: la cancellazione della sua future rappresenta il
  trasporto. LOCAL è verificato separatamente con un vero worker che ignora l'interruzione,
  contando il lavoro attivo invece dello stato cancellato della future.
- Aggiunti `CoreCapacityRegistrationTest` e `QueueLeaseGenerationTest` in entrambi i moduli,
  con completamenti vecchio/prima-nuovo e nuovo/prima-vecchio, rilascio duplicato, aggiornamento
  della configurazione e governor. Aggiunti il budget globale della traversata, la scadenza
  prima del wake-up e il timeout del trasporto EXTERNAL.
- Suite core completa senza filtri: 615 test, 3 skip. Ha riprodotto i due R8 già previsti
  per P09/P10 e individuato un vecchio test che esigeva il forwarding del timeout del waiter.
  Quest'ultimo è stato corretto e rinominato con GitNexus per verificare il contratto P05.
- Verifica finale del core con **sola esclusione di `R8HistoryCleanupRegressionTest`**:
  613 test, 0 fallimenti, 3 skip. L'esclusione è in un init script temporaneo, non nella
  configurazione del repository. I due R8 restano presenti e rossi nella suite ordinaria.
- Suite complete: async-queue 64 test / 0 fallimenti; sync-queue 89 / 0 / 3 skip;
  offload 26 / 0; workload-metrics 5 / 0; concurrency-control 64 / 0 / 1 skip.
- Controlli OpenAPI, copertura contratto e architettura core ripetuti dopo la modifica
  della documentazione: `BUILD SUCCESSFUL`.

Comandi principali, eseguiti con cache locale e `--offline`:

```bash
./gradlew :control-plane:test :control-plane-modules:async-queue:test \
  :control-plane-modules:sync-queue:test :control-plane-modules:offload:test \
  :workload-metrics:test :control-plane-modules:concurrency-control:test \
  --continue --no-parallel --console=plain --offline
# La suite ordinaria conserva i due R8 rossi di P09/P10.

# /tmp/nanofaas-review-exclude-known-r8.gradle contiene esclusivamente:
# allprojects { tasks.withType(Test).configureEach {
#   filter { excludeTestsMatching '*R8HistoryCleanupRegressionTest' }
# } }
./gradlew -I /tmp/nanofaas-review-exclude-known-r8.gradle :control-plane:test \
  :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test \
  :control-plane-modules:offload:test :workload-metrics:test \
  :control-plane-modules:concurrency-control:test \
  --continue --no-parallel --console=plain --offline

./gradlew :control-plane-modules:offload:test --no-parallel --console=plain --offline
./gradlew :control-plane:test --tests '*OpenApi*Test' --tests '*IssueCoverageTest' \
  --tests '*CoreArchitectureTest' --no-parallel --console=plain --offline
```

L'ultima esecuzione aggregata ha richiesto un aggiustamento nella nuova fixture offload:
la scadenza del waiter restituisce `SyncInvocation` con stato `timeout`, non una future
completata eccezionalmente. Dopo l'aggiustamento la suite offload completa è verde.
Le altre suite erano già verdi e non sono state modificate successivamente.

**Misura indicativa del costo ordinario.** Harness temporaneo
`/tmp/nanofaas-outcome-bench/OutcomeInsertionBench.java`, stessa JVM OpenJDK 25.0.4,
`-Xms256m -Xmx256m`, 150.000 warm-up e tre round di 300.000 operazioni per payload.
Confronta freeze + pesatura Caffeine della base con freeze + peso memorizzato del fix;
non misura HTTP, concorrenza o l'intera inserzione Caffeine. Allocazioni lette tramite
`ThreadMXBean`, tempi riportati come mediana dei tre round:

| Payload | Base ns/op | Fix ns/op | Base B/op | Fix B/op |
|---|---:|---:|---:|---:|
| Stringa `ok` | 130,1 | 121,9 | 776 | 440 |
| Mappa piccola con stringhe, intero e lista | 264,0 | 136,3 | 1.448 | 768 |

Misura locale esplorativa, non un nuovo SLO. Le allocazioni diminuiscono eliminando la
seconda traversata; i tempi brevi risentono di warm-up e carico della macchina.

**Compatibilità e limiti.** Nessun cambiamento alle quote configurate o alla semantica
pubblica delle chiavi. La pesatura più conservativa può trattenere meno outcome a parità
di budget: il moltiplicatore storico 116 B non è una promessa sul numero di risultati
leggibili. Tipi opachi possono produrre un successivo 410 pur conservando il tombstone.
Le metriche di durata dello slot async contano anche una lease restituita dopo un probe
che trova la coda vuota. Non sono stati eseguiti soak, build native o scenari NanoLab.
R6 resta assegnato a P08 e R8 a P09/P10; questo intervento non li dichiara risolti.

**Impact e integrazione.** Analisi nel checkout `/home/michele/Documenti/nanofaas`, stesso
HEAD della review, indice ricostruito preservando gli embedding. Impact upstream dei
metodi e delle classi prima delle modifiche; HIGH/CRITICAL comunicato per store, lifecycle,
handler, coordinator e capacità. Collegamenti UNKNOWN verificati con lettura dei sorgenti
e ricerca dei riferimenti. Le fixture JUnit sono entry point del framework. Controllo
GitNexus delle modifiche prima del commit, oltre a `git diff --check` e review del diff.
Le modifiche preesistenti dell'esperimento overload e le skill non tracciate restano fuori
dal commit delle correzioni.

**Prossimo passo.** Integrare il branch delle correzioni nel branch della campagna e
riprendere da P07. Il merge e il soak restano successivi a questa consegna.

Controllo finale GitNexus: scope `all` = 41 file / 205 simboli / 58 flussi; scope
`staged` = 39 file / 204 simboli / 58 flussi, rischio `critical`. Entrambe le risposte
restituiscono l'intero elenco (nessun flag partial/truncated, conteggi coincidenti con
le lunghezze degli elenchi). I due file aggiuntivi di `all` sono quelli overload
preesistenti, non staged. I flussi modificati riguardano il perimetro atteso: admission,
completion, store, capacità e scheduler. `git diff --cached --check` supera il controllo.

## 2026-09-09 — Revisione del piano dopo le correzioni P00–P06

**ID e stato:** revisione documentale del piano P00–P25, autorizzata insieme al
commit e al merge nel branch `control-plane-lifecycle-memory`. Base della revisione:
`47bc70fa` su `fix/p00-p06-review`; destinazione prima dell'integrazione: `c8d6dd02`.
Questa voce aggiorna il prossimo passo dell'appendice precedente: si riparte da
**P20a**, poi P08, P09/P10 e P07 secondo il nuovo ordine. Nessun nuovo task di
implementazione viene dichiarato eseguito dalla sola revisione del piano.

**File e contenuto:** piano esistente e questo registro, senza nuovi file locali
tracciati. Conservati gli ID e tutti i rilievi. Introdotti P20a/P20b per separare
contratti interni preliminari ed estrazione successiva alla baseline; P07a–P07e
per ownership, ingresso, input/esecuzioni, waiter e calibrazione; P16a/P16b per
permettere i fix Python/Java-lite prima della conformità completa dei cinque SDK.
Eliminate dipendenze non necessarie di P08/P09/P12; esplicitata la dipendenza
P11 da P07 per il bound dei wake-up. P20/P22 descrivono il residuo rispetto alle
lease e alla capacità core già implementate, evitando di rifare il lavoro.

**Criteri nuovi:** contabilità fino alla fine fisica del lavoro che conserva
payload; identità di generazione condivisa senza registry storici illimitati;
progressi distinti tra implementazione, test mirati e integrazione; gate sui
consumer reali con cancellazione HTTP e worker LOCAL osservabili; fallimenti
noti elencati per metodo/owner; checkpoint brevi di carico e drain prima dei gate
completi. P19, P23 e il soak P24 mantengono i propri requisiti. Il redesign degli
scheduler rimane nella issue #208.

**Verifiche documentali:** rilettura del diff, 26 intestazioni P00–P25 preservate,
destinazioni dei link locali presenti e blocchi di codice bilanciati. Controllo
del grafo delle dipendenze espanso nei sottotask: 35 nodi senza cicli. Riscontro
dei tre nomi di metodo R6/R8 nei sorgenti. `git diff --check` senza errori.
Nessun simbolo produttivo o test modificato: impact upstream non applicabile;
il controllo GitNexus pre-commit riguarda lo scope documentale staged.
Le suite applicative non vengono ripetute per questa modifica documentale;
restano le evidenze e le limitazioni della review `47bc70fa`, inclusi R6/R8 aperti,
native/NanoLab/soak non eseguiti.

**Integrazione prevista:** commit dei soli due documenti e fast-forward del
branch della campagna, verificando gli SHA e la preservazione dei file overload
e delle skill non tracciate. L'esito effettivo è riscontrabile nei riferimenti
Git del branch; questo paragrafo non anticipa il successo del merge. Nessuna
variazione di API o default introdotta dalla revisione documentale.

## P20a — Internal contracts needed before P07–P11

**ID:** P20a — the semantic prerequisite of P20: verify the P06 consumers, record the residual
inventory, extend the lifecycle ADR with ownership by scope, and introduce the generation identity
and the active/retiring/closed protocol as small reusable contracts in the existing core packages.
Not the closure of P20: no JAR extraction, no mass class move, no meter/snapshot/gate
implementation (those stay with P09/P10/P11), no removal of the temporary adapters (P20b/P21).

**Revision / git state**

- Branch: `control-plane-lifecycle-memory`. BASE: `92aae4e9` ("Refine lifecycle campaign
  dependencies and validation gates"). This entry's commit is the P20a commit. The pre-existing
  uncommitted files of the overload experiment (`docs/experiments/overload-path-2026-09/STATO.md`,
  `run-queue-2.out`) and the untracked GitNexus skills are deliberately left out of the commit.

**Changed files**

- New (core, `it.unimib.datai.nanofaas.controlplane.capacity`):
  - `FunctionGeneration` — the shared identity of one incarnation of a function name
    (`functionName` + monotonic id), minted by `FunctionCapacityRegistry`, with `supersedes` to
    tell a stale event from a current one. Internal only: never an execution id, a key component
    or a metric tag.
  - `GenerationPhase` — `ACTIVE -> RETIRING -> CLOSED` (plus the direct `ACTIVE -> CLOSED` when a
    generation is retired holding nothing); `CLOSED` is terminal.
  - `GenerationLifecycle` — the reusable state machine plus the count of retained resources:
    `retain`/`retainIfBelow` are admitted only while `ACTIVE`, and the transition to `CLOSED` is
    reported to exactly one caller. It carries no identity and fires no callback on purpose: the
    owner pairs it with its own `FunctionGeneration` and runs its cleanup outside its own locks,
    so no owner's lock order is inverted by this class.
- Applied to the capacity already in place:
  - `FunctionCapacityState` now delegates active/in-flight/drained bookkeeping to
    `GenerationLifecycle` (a slot is one retained resource, `deactivate()` is the retirement
    transition) and exposes `phase()`. Same semantics, with one intentional tightening: a repeated
    `deactivate()` on an already closed generation no longer re-fires the drain callback.
  - `DispatchLease` carries a `FunctionGeneration` instead of `(String, long)`; `functionName()`
    is derived from it. No second lease concept, no duplication of P06's lease.
  - `FunctionCapacityRegistry` mints `FunctionGeneration`, keys the draining map by it, and adds
    `activeGeneration(name)` — the read the other per-function owners (P08–P11) use to fence a
    stale event without a new global registry.
- `docs/architecture/0001-execution-lifecycle-contract.md`: §3.3 and §3.4 updated to what P05/P06
  actually delivered (including the carried-forward per-attempt-deadline note: the deadline is
  applied to a mirror of the raw transport future, so the retry policy runs while the lease stays
  with the physically running work; the offload hop's derived budget remains the one documented
  conflation). New §8.1 (ownership by scope: execution, waiter, attempt, generation — acquisition,
  release, cancellation, retry, shutdown, plus terminal future vs physical end and cancellation
  requested before the handle is published), new §8.2 (generation identity and the
  active/retiring/closed protocol, with the five rules), a status paragraph in §9, and new §10
  (residual inventory after P06).
- Tests: new `GenerationLifecycleTest` (8) and `FunctionGenerationTest` (4); two cases added to
  `FunctionCapacityRegistryTest` for remove/re-register identity + late release fencing and for
  "a generation closes only once its last slot comes back".

**Residual inventory (the verified part, full table in ADR §10)**

Read from the call sites, not assumed. Two findings worth naming here:

- The whole name-based **acquisition** chain (`FunctionCapacityRegistry.tryAcquireSlot`,
  `InvocationEnqueuer.tryAcquireSlot` and its four implementations, `QueueManager.tryAcquireSlot`)
  has **no production caller left**: every remaining call site is a test or a test double that
  simulates `tryAcquireLease`. It is a removal candidate for P20b, not a live path.
- The name-based **release** (`releaseSlotAndGetHoldNanos` through
  `InvocationEnqueuer.releaseDispatchSlot`) is still called, but is inert in practice:
  `releaseAttemptCapacity` returns early once the transport owns the capacity, and the registry's
  `leased` identity map prevents it from consuming a lease-owned slot. It goes with the `leased`
  map in P20b.
- `FunctionCapacityState.incrementInFlight()` (an increment with no ceiling) and the two public
  `FunctionCapacityState` constructors are reachable only from tests; `hasGeneration(name)` answers
  "any generation, including one only draining", while the two `SyncQueueService` guards that use it
  are really asking `activeGeneration(name) != null`.

**Impact and graph checks**

- GitNexus index up to date at `92aae4e9` before starting. `impact --direction upstream` before
  editing: `FunctionCapacityRegistry` **HIGH** (43 impacted, 23 direct, 3 processes),
  `DispatchLease` **CRITICAL** (19 impacted, 8 direct), `FunctionCapacityState` **HIGH** (13
  impacted, 4 direct), `DispatchAttempt` LOW. No `UNKNOWN` verdict was returned.
- The CRITICAL warning on `DispatchLease` was not waived: the dependent list was read symbol by
  symbol, which showed that every dependent only *carries* the lease and that `generation()` is read
  in exactly two module tests (`QueueLeaseGenerationTest` in async-queue and sync-queue), both of
  which compare identities and stay valid with a record. Every dependent module's suite was then run
  (see below).
- `detect-changes` before committing (see the command block); the architecture rule
  `core_packages_are_free_of_cycles` was re-run to confirm the new types keep `capacity` a leaf
  package.

**Test commands and outcomes**

```bash
./gradlew :control-plane:test --tests '*FunctionCapacityRegistryTest' \
  --tests '*GenerationLifecycleTest' --tests '*FunctionGenerationTest' \
  --tests '*DispatchLifecycleAndCancellationTest' --tests '*ReviewLifecycleGateTest' \
  --tests '*CoreCapacityRegistrationTest' --console=plain --offline
# BUILD SUCCESSFUL

./gradlew :control-plane:test --no-parallel --console=plain --offline
# 629 tests completed, 2 failed, 3 skipped — the 2 failures are the known R8 regressions
# (R8HistoryCleanupRegressionTest), owned by P09/P10 and red before this task as well.

./gradlew :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test \
  :control-plane-modules:offload:test :control-plane-modules:concurrency-control:test \
  :workload-metrics:test --continue --no-parallel --console=plain --offline
# BUILD SUCCESSFUL

./gradlew :control-plane-modules:autoscaler:test :control-plane-modules:k8s-deployment-provider:test \
  :control-plane-modules:runtime-config:test :control-plane-modules:build-metadata:test \
  --continue --no-parallel --console=plain --offline
# BUILD SUCCESSFUL

./gradlew :control-plane-modules:container-deployment-provider:test --console=plain --offline
# 66 tests, 1 failed — the pre-existing R6 red (P08), unchanged by this task.

./gradlew :control-plane:test --tests '*OpenApi*Test' --tests '*IssueCoverageTest' \
  --tests '*CoreArchitectureTest' --no-parallel --console=plain --offline
# BUILD SUCCESSFUL
```

**Progress classification**

- **Implemented:** the three contracts, their application to `FunctionCapacityState`,
  `DispatchLease` and `FunctionCapacityRegistry`, the ADR ownership table and the residual
  inventory.
- **Verified with targeted tests:** the active/retiring/closed protocol (including the
  exactly-one-close report under contention), the identity across remove/re-register, and the late
  release that must not touch the new generation.
- **Verified in integration of the involved paths:** the already migrated consumers — core
  dispatch, both queue modules, offload, the governor and workload metrics — all still green on
  their own suites, with the two known R8 reds and the R6 red unchanged.
- No load run, no native build, no NanoLab scenario, no soak: this task changes contracts and
  documentation, not admission or transport behavior.

**Measures**

None taken: P20a introduces no new limit, default or measurable path. The one performance-relevant
choice is deliberate — the acquisition ceiling is applied by `retainIfBelow(int)` rather than a
predicate, so the hot dispatch path allocates nothing it did not allocate before.

**Documented incompatibilities**

- `DispatchLease.generation()` returns a `FunctionGeneration` instead of a `long`. Internal API,
  no public/HTTP surface, no configuration or metric changes; the only readers were the two
  module tests named above.
- A repeated `FunctionCapacityState.deactivate()` no longer re-fires the drain callback. The
  registry's drain handler was already idempotent, so this only removes a duplicate notification.
- `activeGeneration(name)` is added, `hasGeneration(name)` is unchanged: the second still answers
  "any generation, including one only draining". The two are not interchangeable, and switching the
  `SyncQueueService` guards belongs to P20b.
- R6 (P08) and R8 (P09/P10) remain open and explicitly red; nothing here declares them closed.

**Next step**

P08 (recoverable partial deprovision), then P09/P10 and P07, in the order set by the plan
revision. P08–P11 consume §8.2 and `activeGeneration(name)` for their own generation-scoped
owners; P20b removes the adapters listed in ADR §10 once those consumers have a port to move to.

## P08 — Recoverable partial deprovision (R6)

**Task:** P08 — "Rendere recuperabile il deprovision parziale". **Plan revision:** the 2026-09-09
revision of `docs/plans/2026-09-08-control-plane-lifecycle-memory-and-modularity.md`. **Base
commit:** `397e2e01` (P20a), branch `control-plane-lifecycle-memory`.

**What changed**

The container backend used to drop a function's only tracking reference before knowing its
containers were gone, and to skip the proxy close entirely once a removal threw: a failed cleanup
lost both the ability to retry and the handle on a running HTTP server, executor and client (R6,
invariant I10). The control plane then restored the function as if nothing had happened.

- `ContainerLocalDeploymentProvider.deprovision` now releases the proxy on a guaranteed path
  *first* (the removal decision is already taken, so the endpoint must stop admitting traffic before
  replicas start disappearing), then attempts every container in one pass — the tracked replicas
  plus whatever the runtime still reports under this function's managed labels — collecting errors
  instead of stopping at the first. A replica leaves the map only once its container is confirmed
  gone, and the tracked state (and its lock) are dropped only when nothing is left. What could not
  be released is reported as `PartialDeprovisionException`, naming it.
- `removeReplica` (the scale-down path) got the same rule: confirm, then forget.
- `ContainerLocalDeploymentProvider` is now `AutoCloseable`: context shutdown closes the proxies and
  removes no container, so a restarted provider rediscovers them from their labels.
- A function in pending removal is refused by `provision` and ignored by `setReplicas`, so nothing
  adopts or grows a deployment that is still owed a cleanup.
- `RoundRobinFunctionProxy.close` flips the admission gate first and unconditionally, then runs
  every release stage even if an earlier one throws; a stage that fails leaves the proxy unreleased
  and rethrows, so the owner's retry re-runs all of them (each is idempotent).
- `FunctionService.remove` distinguishes the two failure outcomes. A `PartialDeprovisionException`
  goes to `holdPendingRemoval`: the catalog entry is restored (that entry is what keeps the
  leftovers traceable and makes a retried DELETE resume the cleanup), the registration listeners are
  **not** replayed, and every path into the function — invoke, enqueue, patch, replicas,
  re-registration — is refused with `409 FUNCTION_REMOVAL_PENDING`. Any other failure keeps the
  existing `rollbackRemoval`, which is only honest because the deprovision succeeded and
  `reconcile` rebuilt and verified the deployment first. The same rule applies to the registration
  rollback path, so a failed registration whose cleanup fails does not orphan its containers.
- `ManagedDeploymentCoordinator.deprovision` invalidates the cached replica status even when the
  provider throws.
- API behaviour documented in `openapi/core.yaml` (DELETE 409 plus invoke/enqueue 409) and in ADR
  §8.3, with the §8 deployment row and the §9 conformance table updated.

**Files changed**

- `platform/control-plane/src/main/java/.../controlplane/deployment/PartialDeprovisionException.java` (new)
- `platform/control-plane/src/main/java/.../controlplane/registry/FunctionRemovalPendingException.java` (new)
- `platform/control-plane/src/main/java/.../controlplane/deployment/ManagedDeploymentProvider.java`
- `platform/control-plane/src/main/java/.../controlplane/registry/FunctionService.java`
- `platform/control-plane/src/main/java/.../controlplane/registry/ManagedDeploymentCoordinator.java`
- `platform/control-plane/src/main/java/.../controlplane/api/GlobalExceptionHandler.java`
- `platform/modules/container-deployment-provider/src/main/java/.../ContainerLocalDeploymentProvider.java`
- `platform/modules/container-deployment-provider/src/main/java/.../RoundRobinFunctionProxy.java`
- `platform/control-plane/src/test/java/.../controlplane/registry/FunctionServicePartialDeprovisionTest.java` (new)
- `platform/control-plane/src/test/java/.../controlplane/api/GlobalExceptionHandlerTest.java`
- `platform/modules/container-deployment-provider/src/test/java/.../ContainerLocalDeprovisionRecoveryTest.java` (new)
- `platform/modules/container-deployment-provider/src/test/java/.../R6DeprovisionFailureOwnershipRegressionTest.java`
- `platform/modules/container-deployment-provider/src/test/java/.../RoundRobinFunctionProxyTest.java`
- `openapi/core.yaml`, `docs/architecture/0001-execution-lifecycle-contract.md`,
  `docs/experiments/lifecycle-memory-2026-09/STATO.md`

**Impact (GitNexus, `--direction upstream`, run before editing)**

| Symbol | Risk | Impacted / direct |
|---|---|---|
| `RoundRobinFunctionProxy` | **HIGH** (82 with tests) | 82 / 14 |
| `ManagedDeploymentProvider.deprovision` | **HIGH** | 23 / 3 |
| `FunctionService.get` | **HIGH** | 45 / 18 |
| `FunctionService.getRegistered` | **HIGH** | 44 / 7 |
| `FunctionService.remove` | MEDIUM | 12 / 12 |
| `ContainerLocalDeploymentProvider` | LOW | 3 / 2 |

No `UNKNOWN` verdict, so none had to be resolved by a text search. The HIGH verdicts were not
waived: `RoundRobinFunctionProxy`'s public surface is unchanged except for `close()` becoming
retryable, and every dependent listed (the factory, the provider's provision/reconcile, and the two
suites) was compiled and run; `FunctionService.get`'s dependent list is inflated by `Optional.get`
and `List.get` name matches, and the added behaviour only triggers for a name in pending removal,
which no pre-existing path can enter. `getRegistered` was deliberately **not** changed, so a
partially removed function is still listed and readable — that is what "the catalog represents the
state actually obtained" means here.

`detect-changes --scope all` before committing: recorded with the commit (see below).

**Test commands and outcomes**

```bash
# RED, on the P08 base with the fix stashed (the pre-existing R6 regression):
git stash push -- platform openapi
./gradlew :control-plane-modules:container-deployment-provider:test \
  --tests '*R6DeprovisionFailureOwnershipRegressionTest*' --console=plain --offline
# FAILED at R6DeprovisionFailureOwnershipRegressionTest.java:128 —
# "a failed deprovision must not drop the proxy without closing or tracking it"
git stash pop

# GREEN, with the fix:
./gradlew :control-plane-modules:container-deployment-provider:test --console=plain --offline
# BUILD SUCCESSFUL — 77 tests, 0 failed (66 before: +10 recovery cases, +1 proxy close-stage case)

./gradlew :control-plane:test --tests '*FunctionServicePartialDeprovisionTest*' --console=plain --offline
# BUILD SUCCESSFUL — 5 tests

./gradlew :control-plane:test --no-parallel --console=plain --offline
# 635 tests completed, 2 failed, 3 skipped — the 2 failures are the known R8 regressions
# (R8HistoryCleanupRegressionTest), owned by P09/P10 and red before this task as well.

./gradlew :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test \
  :control-plane-modules:offload:test :control-plane-modules:concurrency-control:test \
  :control-plane-modules:autoscaler:test :control-plane-modules:k8s-deployment-provider:test \
  :control-plane-modules:runtime-config:test :control-plane-modules:build-metadata:test \
  :control-plane-modules:container-deployment-provider:test :workload-metrics:test \
  --continue --no-parallel --console=plain --offline
# BUILD SUCCESSFUL

./gradlew test --no-parallel --continue --console=plain --offline
# Only the same 2 R8 failures across the whole repository.

./gradlew build -x test --console=plain --offline
# BUILD SUCCESSFUL
```

**Progress classification**

- **Implemented:** the four requirements — ownership kept until confirmation, every resource
  attempted with collected errors and a guaranteed proxy path, the explicit partial outcome shared
  by provider and `FunctionService`, and discovery/restart plus context shutdown.
- **Verified with targeted tests:** one failing replica among several; a failing proxy close; a
  successful second attempt; a failing discovery; a scale-down whose removal fails; provider
  stop/start with rediscovery; context shutdown; the reconcile rebuild out of pending removal; the
  proxy's close-stage failure and retry.
- **Verified in integration of the involved paths:** `FunctionService.remove`, the registration
  rollback, the invocation lookup, patch, replicas and re-registration under pending removal, plus
  the removal racing an invocation lookup; the whole control-plane and module suites are green
  except the two R8 reds owned by P09/P10.
- No load run, no native build, no NanoLab scenario, no soak.

**Measures**

None taken: P08 adds no limit, default or tunable. The removal path gains one
`listManagedContainers` call per deprovision (a delete-path call, not a request-path one) in
exchange for the metadata-driven recovery; nothing on the invocation path changed except one
`ConcurrentHashMap` lookup in `FunctionService.get`, which returns null in every non-pending case.

**Documented incompatibilities**

- `ContainerLocalDeploymentProvider.deprovision` now throws `PartialDeprovisionException` instead of
  the raw adapter exception; the adapter's own failure travels as the cause. The R6 regression
  test's type assertion was updated accordingly (its ownership assertions — the part that was red —
  are unchanged).
- `DELETE /v1/functions/{name}` can now answer `409 FUNCTION_REMOVAL_PENDING` instead of propagating
  a `500`, and a function in pending removal answers `409` on invoke, enqueue, patch, replicas and
  re-registration. Documented in `openapi/core.yaml`.
- `ContainerLocalDeploymentProvider` implements `AutoCloseable`, so Spring now calls `close()` on
  context shutdown (inferred destroy method). It closes proxies only; containers are left recoverable.
- `KubernetesManagedDeploymentProvider` was **not** changed: it still throws raw exceptions, so a
  failed k8s deprovision keeps today's restore-and-reconcile behaviour. Adopting the partial
  outcome there is a follow-up, not part of P08's file list.
- Across a restart, a function left in pending removal is restored from the catalog and reconciled
  like any other: if the rebuild succeeds it serves again under a new generation, which is the
  "rebuilt and verified" case ADR §8.3 allows, and the operator re-issues the delete.

**Next step**

P09/P10 (R8: removed-name sets and replica-snapshot entries) and P07 (aggregate admission budgets),
in the order set by the plan revision. A Docker-backed NanoLab scenario for the partial-deprovision
path is owned by NanoLab (separate checkout) and is out of scope here.

## 2026-09-09 — P09/P10, prima correzione R8 (non ancora task completi)

Base: `397e2e01` (P20a/P08). GitNexus aggiornato con `analyze --index-only` prima delle
modifiche. L'impact su `Metrics` è CRITICAL (25 dipendenze, 8 dirette, cinque flow); quello su
`ReplicaStatusSnapshot` e `ManagedDeploymentCoordinator` è MEDIUM. Il successivo
`detect-changes --scope all --repo .` riporta 21 simboli e rischio CRITICAL, quindi i flow
di invocazione/completion e scaling restano da verificare integralmente.

- Implementato: `Metrics` conserva solo registrazioni correnti, non nomi rimossi; il churn di
  1.000 nomi non lascia tombstone. `ReplicaStatusSnapshot.invalidate` stacca la Entry dalla
  mappa (la callback tardiva rimane confinata nella Entry staccata), e il pool di refresh non è
  più statico: è per-snapshot, con due worker, coda 2, `AbortPolicy` e shutdown posseduto dal
  `ManagedDeploymentCoordinator`.
- Test mirati green:
  `./gradlew :control-plane:test --tests '*MetricsTest*' --tests '*R8HistoryCleanupRegressionTest*' --tests '*ReplicaStatusSnapshotTest*' --console=plain --offline`.
  La suite `:control-plane:test --no-parallel --console=plain --offline` non ha XML con failure/error.
- Non dichiarare P09/P10 completi: restano da migrare `SyncQueueMetrics`, `SyncQueueService` e
  i meter lazy dell'offload all'owner di generazione, e P10 richiede ancora osservazioni
  fresh/stale/unavailable, deadline/cancellazione e metriche del refresh. P07 non è iniziato.

Aggiornamento: `SyncQueueMetrics` ora conserva anch'esso soltanto registrazioni correnti; il test
di churn di 1.000 nomi e `:control-plane-modules:sync-queue:test --tests '*SyncQueueMetricsTest*'
sono green. `SyncQueueService` e offload restano esplicitamente aperti.

Secondo aggiornamento: il marker di `SyncQueueService` viene eliminato quando drena una
generazione realmente registrata; una rimozione senza generazione mantiene invece il rifiuto
difensivo per compatibilità con l'API interna. Aggiunto churn di 1.000 generazioni e verificato
con `:control-plane-modules:sync-queue:test --tests '*SyncQueueServiceTest*'` green.

### Ripresa SDD — rettifica delle evidenze precedenti

HEAD di lavoro: `fa3118e896293ece0f46179bdc663500f484d48d`; le modifiche P09/P10
sono ancora non committate. La base `397e2e01` sopra identifica P20a, non include
la successiva implementazione P08.

La review ha respinto il marker difensivo permanente e il successivo tentativo
di consumarlo al primo enqueue: il secondo enqueue tardivo avrebbe potuto passare.
Il fix successivo conserva owner associati alla generazione e agli execution ID
ancora live, con rilascio su settlement/expiry/drain. Offload ripristina il
costruttore `Supplier<MeterRegistry>` e rimuove gli owner legacy dopo l'ultima
sottoscrizione. La re-review di questo fix è in corso.

L'implementer ha registrato esiti finali `BUILD SUCCESSFUL` per le suite
`:control-plane-modules:sync-queue:test` (93 test, 3 skipped) e
`:control-plane-modules:offload:test` (28 test), entrambe senza failure/error.
Questa evidenza riguarda quel fix; non certifica modifiche successive.
La precedente sola scansione degli XML core non certifica il completamento della
suite integrale. L'errore di compilazione per erasure dei costruttori non vale
come riproduzione RED comportamentale richiesta dal piano.

P09 resta aperto: la completion core usa ancora il nome per aggiornare contatori
e timer e deve essere vincolata all'owner acquisito dall'esecuzione. P10 resta
parziale; P07 non è iniziato. Nessun gate globale o soak è dichiarato eseguito.

### 2026-09-09 — Resumed after a session interruption: verifying and closing out P09

Resumed after a session-limit interruption. The worktree held uncommitted changes to
`Metrics`, `SyncQueueMetrics`, `SyncQueueService`, `DefaultOffloadGateway` (legacy offload
owner + generation), `ReplicaStatusSnapshot` and `ExecutionStore`, produced by earlier runs
of this same campaign (including a review/fix round recorded above). Compile break:
`OffloadConfiguration` still wired `MeterRegistry` while `DefaultOffloadGateway` had moved
to `Metrics`; fixed (no behavior change, only the Spring bean). Four red tests for the same
reason (`ExecutionCompletionHandlerTimingTest` and the three in `InvocationPathAccountingTest`):
they used `Metrics` without ever calling `registerFunction`, a behavior accepted before P09's
whitelist made it explicit. Fixed by adding the explicit registration in their respective
`@BeforeEach`/tests, consistent with the real constraint (a function must be registered
before it produces metrics).

**Final verification:** `./gradlew test --no-parallel --continue --console=plain --offline`
→ `BUILD SUCCESSFUL` across the whole repository. `:control-plane:test` 637 tests, 0 failed,
3 skipped (including `R8HistoryCleanupRegressionTest`, now green on both assertions: metric
names and snapshot entries do not accumulate). `:control-plane-modules:sync-queue:test` 93
tests, 0 failed. `:control-plane-modules:offload:test` 28 tests, 0 failed.
`detect-changes --scope staged`: 16 files, 146 symbols, 41 flows, risk `critical`, no
`partial`/`truncated`.

**Gap closed in a second commit of the same resumption:** `ExecutionCompletionHandler`
recorded core completion counters/timers (`success`, `error`, `coldStart`, `warmStart`, the
timers, `retry`) by function name alone, without binding them to the generation the
execution was admitted under — a point violation of invariant I7 in the rare case of a
same-name remove+re-register racing an in-flight dispatch. Added
`ExecutionRecord.currentGeneration()` (a peek, not a take, of the already-attached lease: it
does not touch release accounting) and `Metrics.isCurrentGeneration` (true when there is no
captured generation — offload, or a name-released path — or when it matches the capacity
registry's active generation). The generation captured BEFORE `releaseAttemptCapacity`
threads through `FinalCompletion` to every write site; `completeUnderLock`, `handleRetry`,
`publishFinalCompletion`, `handleAdministrativeExpiry` and `recordTerminalConclusionOnce` are
all guarded. For `completeOffloadedExecution`/`failOffloadedExecution` the guard is a
documented no-op: offload calls never acquire a lease, so there is no admission-time
generation captured to compare against today — a real, disclosed limitation, not a false
claim of closure.

New test `CompletionMetricsGenerationFenceRegressionTest`: admits a function under a direct
lease, removes and re-registers the same name while the dispatch is still pending, then
completes the old attempt late. RED on the baseline (the new generation's `success` counter
incremented by the stale completion); GREEN with the fix (the new generation's
`success`/`error` counters stay zero, while the execution's own state still concludes
normally in `SUCCESS` — only the meter write was suppressed, not the transition).
Verification: `./gradlew :control-plane:test --tests
'*CompletionMetricsGenerationFenceRegressionTest*' --console=plain --offline` GREEN after the
fix; `./gradlew :control-plane:test --no-parallel --console=plain --offline` → 638 tests, 0
failed, 3 skipped; `./gradlew test --no-parallel --continue --console=plain --offline` →
`BUILD SUCCESSFUL` across the whole repository.

**Impact and integration:** index refreshed with `analyze --index-only` before editing (at
the prior P09 commit's HEAD). Impact upstream on `ExecutionCompletionHandler` (class): HIGH,
not waived — its two real dependents (`InvocationService`, `ReactiveInvocationCoordinator`)
did not change call signature and are covered by the green full-repo suite. No `UNKNOWN` on
any production symbol touched. `detect-changes --scope staged`: 4 files, 23 symbols, 21
flows, risk `critical`, no `partial`/`truncated`; the listed flows match the expected
perimeter (CompleteExecution, RetryExhaustedUnderLock, RecordTerminalConclusionOnce).
`git diff --cached --check` passes.

### 2026-09-09 — P09 task review and fix round

Task review dispatched on both P09 commits together (base `fa3118e8`, the already-closed P08
commit; head `bcf1a6bc`). Verdict: **Needs fixes** — spec ❌ on part of acceptance criterion
(b) ("old events do not contaminate the new generation"), 3 Important findings, several
Minor. Adjudicated below with evidence from tracing the actual code, not just the report.

**Important #1 (reviewer): the generation fence only covers direct admission; queue-mediated
dispatch fails open.** Checked against source: `attachDispatchLease` is indeed called from
exactly one call site (inside `dispatchInternal`), but that call site is reached by
`dispatch(InvocationTask)` too, not only `dispatchDirect`. The async-queue `Scheduler`
acquires a real `DispatchLease` via `queueManager.tryAcquireLease` (the SAME
`capacity.DispatchLease` type dispatchDirect uses) and attaches it to the task with
`task.withDispatchLease(lease)` before calling `invocationService.dispatch(task)`, which
reaches `dispatchInternal(task, task.dispatchLease())` with a non-null lease — so
`attachDispatchLease` DOES fire for the async-queue path too. `sync-queue` is a pure
admission/backpressure gate (depth + wait estimate); it does not itself dispatch, so it does
not bypass this. **Adjudication: not reproducible as described; the finding rests on an
incomplete trace of how `task.dispatchLease()` gets populated before `dispatch()` is called.**
Not fixed (nothing to fix); recorded here so a future reviewer does not re-raise it without
re-checking `Scheduler.java:178-193`.

**Important #2 (reviewer): `recordTerminalConclusionOnce`'s guard is inert on the
administrative-expiry path it exists for**, because it re-peeks a lease that
`releaseAttemptCapacity` has, by then, already detached. Checked against source:
`releaseAttemptCapacity`'s very first line returns early whenever
`executionRecord.capacityOwnedByTransport()` is true — and `transportOwnsCapacity()` is set
unconditionally, right after `attachDispatchLease`, for every dispatch that reaches the
non-terminal branch of `dispatchInternal`. So `takeDispatchLease()` — the only place that
clears the field a plain peek would read — is in practice reached only by the legacy
name-released path, not by any lease-bearing dispatch. A dedicated regression test built to
reproduce this exact race (admit under a lease → remove+re-register → force administrative
expiry via a steered Caffeine ticker → assert the e2e timer stays at zero) passed GREEN even
with the peek-based `currentGeneration()` reverted, confirming the race does not manifest on
the current dispatch flow. **Adjudication: the underlying observation (a peek can be stale
once detached) is correct in principle, but not reproducible today given
`capacityOwnedByTransport()`'s short-circuit.** Kept the improvement anyway as a genuine
simplification: `ExecutionRecord` now captures `admittedGeneration` as a stable field set
alongside the lease and cleared only on retry, so `currentGeneration()`'s contract no longer
depends on knowing which release path a caller is on — cheap, strictly more robust, and it
removes the need for callers to reason about `takeDispatchLease()` timing at all. The
reproduction test built for this was deleted rather than kept, since it could not be made RED
on the real baseline and the campaign's convention requires a red regression to actually be
red for the right reason.

**Important #3 (reviewer): the `ReplicaStatusSnapshot` executor change exceeds the bounded
companion-fix scope and is untested.** Confirmed and fixed: `withDefaults` had replaced the
prior unbounded `Executors.newFixedThreadPool(2)` with a bounded
`ThreadPoolExecutor(2, 2, ArrayBlockingQueue(2), AbortPolicy)` — a real backpressure/rejection
policy change for slow provider refreshes, squarely P10's scope (fresh/stale/unavailable
observation semantics, refresh queue/rejection observability), introduced with no test.
Reverted the queue to unbounded (`LinkedBlockingQueue`), keeping only the ownership/`close()`
half (the snapshot now owns and shuts down its own executor instead of leaning on a static,
unclosable pool) — that half is behavior-preserving under normal load and needed no new test
beyond the existing shutdown coverage.

**Minor findings addressed:** stale RED-tense javadoc in `R8HistoryCleanupRegressionTest`
(both halves are green now, not still-red); a duplicate `import java.util.Map;` and a
duplicated `metrics.registerFunction("echo")` call in the sync-queue test module; a
`synchronized (entry)` block in `ReplicaStatusSnapshot.invalidate` that was not re-indented
to the 4-space rule; **this STATO.md entry itself was written in Italian**, against this
campaign's own explicit ruling (see the plan-revision entry above and the 9-09 ledger) that
new prose must be in English — corrected from this entry onward.

**Minor findings deferred (parked, not fixed here):** ~110 lines of dead legacy offload
meter-ownership code (`LegacyMeterLifecycle`/`LegacyMeterLease`/`LegacyMeterOwner`) with no
production caller, kept only for three test constructions; a narrow `RemovalFence`
construction race in `SyncQueueService.removeFunctionState` that could leak one execution id
per name if a record settles between the `inFlightExecutionIds` snapshot and the fence's
`put`; unordered `FunctionRegistrationListener` bean list making `Metrics`'s
initial-registration generation capture nondeterministic relative to the capacity registry's
own listener, with a benign but visible effect (a same-tick `update()` can zero a live
function's offload counters). None of these are load-bearing for P09's acceptance criteria.

**Test-list gaps acknowledged, not closed here:** no test for a sync-gateway toggle mid-flight
(brief-listed); no test for `ReplicaStatusSnapshot.close()` / `ManagedDeploymentCoordinator.close()`
(introduced by this task, but shutdown-path coverage, not R8/I7 correctness — deferred to P10,
which owns this file's full test surface).

**Incompatibility now documented (was missing):** `Metrics.metersOrNull` used to create
meters lazily for any name not explicitly removed; it now requires `registerFunction` to have
been called first. Every production caller already goes through the
`FunctionRegistrationListener` fan-out (`FunctionCatalogRestorer`, `FunctionService`
register/update/remove), so this is safe, but any future caller that skips registration will
silently get no metrics rather than lazily-created ones.

**Next step:** dispatch a scoped re-review of this fix round; then P10 (its full scope:
fresh/stale/unavailable observations, the non-blocking periodic path, a freshness-required
deadline path, refresh observability — the entry-removal/ownership slice landed in the first
P09 commit closes only that one slice, not the task); then P07.

## P10 — Replica snapshot with bounded, non-blocking refreshes

**Task:** P10. **Revision:** working tree on `control-plane-lifecycle-memory`, on top of
`d16c1f34` (P09 fix round).

**Changed files**
- `platform/control-plane/.../deployment/ReplicaObservation.java` (new): sealed observation type.
- `platform/control-plane/.../deployment/ReplicaStatusSnapshot.java`: bounded owned pools,
  `observe`/`refresh` split, deadline, invalidation/task ownership, meters.
- `platform/control-plane/.../config/ReplicaStatusSnapshotConfiguration.java` (new): the snapshot
  is now a context-owned bean (and, being a `MeterBinder`, its meters are bound automatically).
- `platform/control-plane/.../registry/ManagedDeploymentCoordinator.java`:
  `getReplicaStatus`/`getReadyReplicas` replaced by `observeReplicaStatus`; snapshot injected;
  `close()` only closes a snapshot this coordinator created.
- `platform/modules/autoscaler/.../InternalScaler.java`,
  `platform/modules/concurrency-control/.../ConcurrencyGovernor.java`: consume the observation.
- Tests: `ReplicaStatusSnapshotTest` (rewritten), `ManagedDeploymentCoordinatorTest`,
  `R8HistoryCleanupRegressionTest`, `InternalScaler*Test` (5 files), `ConcurrencyGovernorTest`.

**Design decision — the observation type.** `ReplicaObservation` is a *sealed* interface with
`Available(status, state, observedAt)` and `Unavailable(observedAt, reason)`. The sealed split sits
exactly on the distinction that changes what a caller may legally do — "I have a measurement" vs "I
do not" — so there is no accessor anywhere that yields a `ReplicaStatus` without narrowing first:
invariant I9 is enforced by the type, not by convention. FRESH vs STALE rides along as a `state()`
enum because it changes how much to trust the number, not whether it exists. The failure is carried
as a short reason `String`, never a `Throwable`, so a retained observation cannot retain a stack
trace (this is a memory campaign). The distinction propagates exactly one level into each consumer:
`InternalScaler.evaluateAndScale` and `ConcurrencyGovernor.govern` skip the cycle when the
observation is not `Available`; nothing else in their logic changed.

**Design decision — two owned pools.** The periodic path (`observe`) and the freshness-required path
(`refresh`) get separate bounded `ThreadPoolExecutor`s (`RefreshLimits.DEFAULTS` = periodic 2/64,
freshness 4/32, 5s deadline), both owned and shut down by the snapshot, which is now a Spring bean.
A single pool cannot satisfy both "wake-up must not queue behind slow periodic refreshes" (P09's
existing property) and "a forced-fresh read must have a local deadline", because a deadline is only
possible when the fetch runs off the caller's thread. Rejection policy is `AbortPolicy`: past the
bound the refresh fails and is counted, which stale-while-revalidate already tolerates.
`CallerRunsPolicy` is explicitly excluded — it would hand the provider call to the loop thread the
bound exists to protect.

**Impact analysis (before editing).** `impact ReplicaStatusSnapshot --direction upstream`: MEDIUM,
17 impacted, 5 direct. `impact getReplicaStatus -f ManagedDeploymentCoordinator.java --include-tests`:
**CRITICAL**, 53 impacted (30+ of them autoscaler/governor test methods). Not waived: the full
caller list was enumerated and every entry is compile-checked by the signature change — each one was
updated (`InternalScaler`, `ConcurrencyGovernor`, `ManagedDeploymentCoordinatorTest`, the five
`InternalScaler*Test` files, `ConcurrencyGovernorTest`) and the whole suite re-run green. Depth-3
entries reached via `getFreshReplicaStatus` (`DeploymentWakeUpGate`, `FunctionService`) keep their
signature and behaviour, except for the new deadline (below). `impact getReadyReplicas` was MEDIUM/7,
all updated. `detect-changes --scope all` after the work: 15 files, 124 symbols, 23 affected flows,
risk critical — the same blast radius, all of it compile-checked and covered by the green suite.

**Tests, with RED/GREEN characterised per item**
- *Item 4 (freshness deadline) — genuine RED→GREEN.* A test written against the pre-P10 API
  (`refresh` on a provider that never answers, under `assertTimeoutPreemptively(3s)`) failed on the
  baseline with `execution timed out after 3000 ms`: `refresh()` joined the fetch with no bound at
  all. It is green now via `ReplicaStatusUnavailableException` at the deadline
  (`refresh_releasesTheCallerAtItsDeadlineWhenTheProviderNeverAnswers`).
- *Items 1, 2, 3, 5 — new capability, no meaningful red baseline.* The bounded queue, the
  observation type, the queue/rejection/age/duration meters and the task-ownership fence are new
  API; a test for them cannot compile against the old code, so "RED" would only mean "did not
  compile" and is not claimed as a regression baseline.
- *Item 5 (R8 entry removal) — already green from P09, verified to stay green.*
  `R8HistoryCleanupRegressionTest.invalidatedReplicaTargetsAreRemovedNotJustCleared` (1,000
  invalidated entries → 0) still passes through the new `observe` path, and
  `invalidatedEntriesAreRemovedSoRepeatedChurnLeavesNothingBehind` adds repeated invalidation.
- *Brief's test list, all covered* in `ReplicaStatusSnapshotTest`: a provider that does not respond
  while others do (`observe_neverBlocksTheLoopOnAProviderThatDoesNotRespondWhileOthersDo`); a
  saturated queue (`observe_rejectsRefreshesPastTheQueueBoundInsteadOfQueueingThemOrRunningThemOnTheCaller`,
  `refresh_failsWhenTheFreshnessExecutorIsSaturatedRatherThanRunningOnTheCaller` — both assert the
  fetch never ran on the caller's thread); repeated invalidate
  (`repeatedInvalidateWhileAFetchIsBlockedCannotQueueUnboundedNewRefreshes`, exact counts: 201
  submissions → 1 active, 2 queued, 198 rejected); remove/re-register during a GET
  (`aRemovalDuringAGetDiscardsTheAnswerAndDoesNotReinsertTheEntry`,
  `aReRegistrationUnderAnotherBackendDuringAGetDiscardsTheOldBackendsAnswer`); late completion
  (`aCompletionThatLandsAfterTheFreshnessDeadlineStillPopulatesTheCache`); fresh/stale/unavailable on
  a controlled clock (`observe_walksFreshThenStaleThenUnavailableOnAControlledClock`); context
  shutdown (`close_stopsTheOwnedPoolsAndRetiresEveryEntry`, `close_leavesAnInjectedExecutorToItsOwner`).
  Consumer-level: `InternalScalerResilienceTest.scalingLoop_skipsAFunctionWithoutAReplicaReadingInsteadOfTreatingItAsZeroReplicas`
  (and the throwing variant), `ConcurrencyGovernorTest.skipsAFunctionWithoutAReplicaReadingRatherThanGoverningItAsZeroReplicas`.
  Concurrency is driven by latches and an injectable `InstantSource` only; no `sleep` anywhere.

**Commands and outcome**
- `./gradlew :control-plane:test --tests '*ReplicaStatusSnapshotTest*'` on the baseline: 21 tests,
  1 failed (the deadline RED above).
- `./gradlew test --no-parallel` after the work: **BUILD SUCCESSFUL** (whole repository).

**Status of the claims:** *implemented* and *verified with targeted tests* for all five items;
*verified in integration of the involved paths* only as far as the existing Spring-context tests in
`:control-plane` exercise the new bean and the two periodic loops — no soak or E2E run was performed
for this task.

**Documented incompatibilities**
1. `ManagedDeploymentCoordinator.getReplicaStatus` / `getReadyReplicas` are gone, replaced by
   `observeReplicaStatus`. Deliberate: keeping an `int`-returning read would keep the "error becomes
   zero replicas" trap one call away.
2. The periodic path no longer blocks on a first fetch. A function's first autoscaler/governor cycle
   after registration now observes UNAVAILABLE and is skipped; the value is there on the next cycle
   (one poll interval later, 5s by default).
3. `getFreshReplicaStatus` now fails after 5s instead of blocking indefinitely. Under a hung
   provider a wake-up now fails at ~5s rather than at the gate's 30s budget. This is the point of
   invariant I8 (a provider timeout must not occupy wake-up workers indefinitely), but it is a
   visible change in when the caller sees the error.
4. Cancellation is best-effort: `Fetcher.fetch` is a synchronous call with no cancellation hook, so
   invalidation/deadline cancel the task (a queued one is really dropped; a running one is
   interrupted) but cannot abort a provider adapter that ignores interruption. The tests cover the
   ignoring case explicitly.

**Known residual:** `FunctionService`'s fallback `new ManagedDeploymentCoordinator(resolver,
registry, locks)` (used only when no coordinator bean exists, i.e. in tests) creates a snapshot that
owns two pools and is closed only if someone closes the coordinator. Pre-existing shape, now two
pools instead of one; in production the bean path is always taken.

**Next step:** P11 (deployment wake-up), which consumes `getFreshReplicaStatus` and now inherits its
deadline; consider whether the wake-up gate should retry across a deadline failure rather than
completing exceptionally on the first one.

## P07a — Ownership and accounting primitives

**Task boundary:** P07a only, on top of `48b29ca9`. This gate defines ownership and reusable
accounting; it does not attach quotas to ingress, records, queues, offload or waiters, and it chooses
no P07e limit defaults.

### Resource table for P07b–P07e

`FunctionGeneration` remains the per-function incarnation identity. `DispatchLease` remains the
only owner/release capability for a dispatch slot; `ResourceOwner` is not a second dispatch lease.
One `ResourceQuota` instance represents one unit/dimension (execution count, retained-input bytes,
or waiter count), with explicit global and per-function limits. Per-function occupancy spans every
still-draining generation of the same name, while per-generation occupancy fences late release.

| Resource | Unit | Owner | Reservation and publication point | Transfer | Release / physical-drain rule | Rollback on intermediate failure | Gate |
|---|---:|---|---|---|---|---|---|
| Transient HTTP request body | bytes received, including chunked bodies | ingress HTTP exchange | Enforce the transport/body cap before complete aggregation or parsing; these transient bytes are not the retained-input estimate | Once a bounded retained representation exists, ownership moves to that representation; never count an unbounded opaque LOCAL object symbolically | Release transport buffers on consume/cancel/error, but releasing them does not release a retained copy | Abort aggregation and release received buffers; publish no record or queue entry | P07b |
| Admitted logical execution | 1 | `LOGICAL_EXECUTION` owner under the generation active at admission | Reserve global + same-name function capacity before publishing a new live record or queue state; offload uses the same global quota | Replay and retry keep the same logical owner, generation attribution and execution unit; ordinary transfer cannot cross the acquiring generation | Release on the execution's one terminal/abandon transition; physical attempts, transport handles and input copies that outlive administrative terminal remain separately referenced/accounted until their real drain | `ReservationBatch.close()` releases every earlier reservation if record/key/queue publication fails; `commit()` occurs only after ownership is published | P07c |
| Canonical retained input | conservative retained bytes | `CANONICAL_INPUT` owner representing the execution's shared canonical input | Reserve global + same-name function bytes before the representation becomes reachable from live/queued/offload state | Retry and concurrent physical consumers acquire bounded same-generation references to the shared owner; the reservation is not linearly transferred between consumers | Release only after the record base reference and every real reader/queue reference (LOCAL worker, HTTP attempt, callback or queue entry) have drained; waiter timeout, administrative terminal state or outcome archival is insufficient | Admission/publish/enqueue failure closes the batch and the retained representation together | P07b/P07c |
| Additional physical input copy | bytes in that copy | `INPUT_COPY` owner tied to the physical attempt/callback that created it | Reserve before allocating/publishing each extra copy; a shared immutable representation is not an extra copy | May transfer to a successor owner only within the acquiring generation when the same bytes move; otherwise reserve a new copy | Release at that copy's actual deallocation/drain, including after administrative terminal state | Copy/allocation/submit failure closes its reservation immediately | P07c |
| Dispatch capacity | 1 slot | existing attempt-owned `DispatchLease`, carrying `FunctionGeneration` | Existing `tryAcquireLease` before dispatch; no `ResourceQuota` duplicates this ownership | Never transferred: every retry acquires its own lease | Existing idempotent `DispatchLease.release()`, driven by raw physical completion; timeout/cancellation request alone is not release | Existing synchronous-dispatch failure path releases the acquired lease | Existing P06/P20a contract, reused by P07c |
| Raw LOCAL/HTTP/deployment attempt and pending callback | one cancellable handle plus the input/copies it references | `PHYSICAL_ATTEMPT`; dispatch slot, when present, is still the existing `DispatchLease` | Publish the real cancellable handle and keep its resource reservations reachable before exposing completion | A retry is a new physical attempt; only genuinely shared input ownership transfers | Administrative terminal may cancel best-effort, but handle, lease and input-copy accounting remain until raw completion/drain; pending offload/HTTP work counts globally | Synchronous dispatch/acquisition failure closes only resources acquired by that attempt | P07c |
| Queue entry | 1 queued task (bounded by its queue) | queue implementation; task owns an idempotent `QUEUE_ENTRY` reference to canonical input | Execution and input reservations precede enqueue; the entry itself must enter only a bounded queue | Dequeue closes the queue reference after dispatch has retained its own physical-consumer reference; retry reuses the logical execution and canonical input | Remove on dequeue/removal/shutdown; draining an entry does not release bytes still held by active physical work | Refused/throwing enqueue removes no entry and closes the uncommitted admission batch | P07c |
| Attached waiter | 1 subscription/timer per caller, including replay | `WAITER` owner under the execution's function generation | Reserve global + same-name function waiter quota before attaching to the shared future; an existing key bypasses execution admission only, never waiter admission | No transfer across callers; retry is invisible to the waiter | Release on that waiter's terminal delivery, timeout or disconnect only; never release execution/input/attempt ownership | Attachment/timer setup failure closes that waiter's reservation without touching the shared execution | P07d |
| Shared completion future | 1 per logical execution | logical execution | Created with the record after successful admission reservations | Unchanged across retries and waiters | Completes once at logical terminal; completion does not assert that raw physical work has drained | Admission rollback makes it unreachable and closes reservations | Existing I1 contract; P07c/P07d consume it |
| Idempotency binding | 1 key slot | existing `StoredKey` association | Existing atomic key claim precedes execution publication; replay reuses it | Pending → published → terminal remains one association | Existing abandon/expiry/removal semantics; terminal tombstone may outlive outcome | Existing `abandonClaim` on admission failure | Existing R7 budget, composed in P07c |
| Archived outcome | configured retained weight | outcome cache entry | Existing store settlement publishes it at terminal; it is not retained-input accounting | No transfer to waiter or attempt | Eviction/expiry releases outcome weight only; it cannot release input still read by physical work | Existing terminal transaction/tombstone policy | Existing P04 budget; calibration in P07e |
| Executor, timer and callback queue entries | 1 task, with queue-specific byte references accounted above | the component that owns the bounded executor/timer/client | Reserve/enqueue only within that component's declared bound; no caller-runs escape onto loop threads | Ownership follows dequeue to the running task | Owner shutdown drains/cancels queued tasks; running work retains referenced resources until completion | Rejection leaves no hidden task and rolls back any unpublished attempt resources | P07c/P07d and existing P10; defaults/observability in P07e |

### Implemented

- `ResourceOwner` names the four lifetimes P07 must keep distinct: logical execution, physical
  attempt, input copy and waiter. Its identity is diagnostic/internal and is never a metric tag.
- `ResourceQuota` atomically checks and increments explicit global and per-function limits in one
  critical section. It also tracks `FunctionGeneration`, while the function-name aggregate covers
  old and new generations concurrently. A successful reservation is the sole release capability;
  duplicate/stale close is inert and zero entries are removed from the maps.
- `Reservation.transferTo` moves ownership only within the exact acquiring generation and
  invalidates the old handle, so a late callback cannot release the new owner's units. Generation,
  global and function attribution do not move or double.
- `ReservationBatch` tracks a multi-quota admission until publication: uncommitted close rolls back
  in reverse order; commit drops the batch's references and leaves each handle with its owner.
- No ingress/store/queue/offload/waiter consumer is wired and no numeric default is introduced.

### Focused-test verification

- Honest RED 1: `./gradlew :control-plane:test --tests '*ResourceQuotaTest*' --console=plain
  --offline` failed in `compileTestJava` with missing `ResourceQuota`/`ResourceOwner` (27 compiler
  errors).
- Honest RED 2: `./gradlew :control-plane:test --tests
  '*ResourceQuotaTest.transferMovesOneReservation*' --console=plain --offline` failed with missing
  `Reservation.transferTo(FunctionGeneration, ResourceOwner)` (1 compiler error).
- Honest RED 3: `./gradlew :control-plane:test --tests
  '*ResourceQuotaTest.anUncommittedBatchRollsBackEverySuccessfulIntermediateReservation*'
  --console=plain --offline` failed with missing `ReservationBatch` (4 compiler errors, including
  the commit-path test compiled in the same file).
- Honest RED 4: `./gradlew :control-plane:test --tests
  '*ResourceQuotaTest.rollbackFollowsAReservationTransferredBeforePublication' --console=plain
  --offline` compiled and ran, then failed at the zero-occupancy assertion (the superseded handle
  made rollback a no-op). The batch now follows the underlying claim across pre-publication
  transfer.
- GREEN: `./gradlew :control-plane:test --tests '*ResourceQuotaTest*' --console=plain --offline` →
  `BUILD SUCCESSFUL` (10 tests). Coverage includes success, global saturation, same-name
  per-function saturation across generations, rollback, commit, idempotent/double close, transfer,
  late old-generation close and barrier/latch-driven concurrent cap enforcement; no sleeps.

### Integration verification

`./gradlew test --no-parallel --continue --console=plain --offline` → `BUILD SUCCESSFUL` in
2m 20s (190 actionable tasks: 27 executed, 163 up-to-date). This is the required one-time full
repository suite for P07a. It verifies compilation and integration with every module; P07b–P07e
consumer behavior is intentionally not claimed because those gates are not wired by this task.

### P07a fix round 1/5 — generation authority correction

This section preserves the original P07a record while correcting its ownership rule. The resource
table and implemented summary above now supersede the earlier same-name generation-handoff wording:
an ordinary reservation transfer is owner-only and remains attributed to the exact generation that
acquired it. No explicit lifecycle handoff authority exists in P07a.

- `ResourceQuota` now requires the existing `FunctionCapacityRegistry` generation authority.
  Reservation checks and accounting publication occur while the registry's per-function lifecycle
  lock proves that the supplied `FunctionGeneration` is the current active generation.
- A retiring generation may release its already-owned reservation, but cannot reserve new units.
  Once its accounting drains and its map entry disappears, a late callback still cannot recreate
  it. This is the I7 fence for the reusable primitive.
- Cross-generation `Reservation.transferTo` is rejected before any owner or counter mutation.
  Same-generation owner transfer remains atomic and leaves the old handle inert.
- The covering test now obtains identities from the real registry authority. Its regression covers
  both the retired-but-capacity-draining interval and the post-drain absence case; concurrency tests
  remain latch/barrier/future based with no sleeps.

## P07b — Bounded ingress and retained-input measurement

**Task boundary:** P07b only, on top of `3f43602f`. This gate limits invocation request bodies and
defines bounded conservative retained-input measurement. It does not attach execution/input quotas
to records, queues, retries, offload or dispatch (P07c), attach waiter quotas (P07d), or choose the
final NanoFaaS properties/defaults and Helm calibration (P07e).

### Implemented

- Bodies at or below the limit on both invocation routes pass through a highest-precedence WebFlux
  filter. A declared `Content-Length` above the limit is refused before body subscription. For
  chunked/unknown-length bodies, each received `DataBuffer` contributes its readable bytes; the
  first crossing buffer is released and an error cancels the server-side upstream subscription.
  The crossing exception carries 413 through controller advice, while direct filter chains map it
  to the same status. Counters are subscription-local and retained nowhere after the exchange.
- The filter derives its limit from the active JSON decoder that can read `InvocationRequest`.
  Spring's codec has a finite per-decoder default and honors
  `spring.http.codecs.max-in-memory-size`; startup fails rather than proceeding if the matching
  decoder does not expose a positive finite limit. P07b therefore adds no provisional NanoFaaS
  numeric default or public property. P07e may migrate this seam to its final validated configuration.
- `RetainedInputEstimator` accepts JSON-like scalars and explicit arrays. It applies explicit
  depth, container-width, visited-node and retained-byte bounds, identity-tracks cycles/shared
  objects during one call, uses subtraction/division guards before arithmetic, and returns a
  reasoned rejection rather than a frontier token. All lists/maps and arbitrary opaque LOCAL
  objects are rejected before collection methods are invoked; callers must convert collections
  to bounded arrays and retain only those arrays. The estimator has no cross-call state and has
  not yet been wired into execution ownership.
- OpenAPI now documents 413 for both `:invoke` and `:enqueue`, including ordering before replay and
  counting unknown-length bodies. `docs/control-plane.md` distinguishes transient ingress bytes,
  conservative retained-representation bytes, and excluded parser/transport/attempt copies. It
  explicitly makes no RSS, native-buffer or remote-memory claim.

### Systematic-debugging evidence for chunked cancellation

The first real Reactor Netty test attached a cancellation latch to the *client outbound* publisher.
That publisher is upstream of the client transport, not the server body subscription; after bytes
are accepted by Reactor Netty or an early response arrives, cancellation is not guaranteed to
propagate back to that test publisher. The test report also showed that the first filter version's
crossing exception was consumed by `GlobalExceptionHandler` inside the downstream chain and became
500 before the filter's outer `onErrorResume` could observe it.

The single tested hypothesis was that server-owned cancellation/release was correct but the client
latch was the wrong observation point, while the exception needed HTTP status semantics inside the
handler chain. A controlled `MockServerWebExchange` connected a pooled-buffer publisher directly to
the filter: after the crossing it completed with 413, observed upstream cancellation, and both the
accepted buffer (released by decoder unwind) and crossing buffer (released by the filter) had zero
ownership. The real random-port HTTP test remains responsible for unknown-length/chunked 413 and a
subsequent bounded request reaching normal routing; it makes no client-publisher cancellation claim.

### TDD and verification

- Retained estimator RED: the focused test failed in `compileTestJava` with 29 missing-symbol
  errors. An intermediate run exposed a test-fixture error (`List.of` cannot contain null); after
  replacing that fixture, all 8 estimator tests passed. Cases cover flat, deep, wide and large
  shapes, node/work bounds, guarded near-`Long.MAX_VALUE` addition, opaque LOCAL objects, custom
  collection non-traversal, and repeated rejection followed by acceptance.
- Initial HTTP RED on the baseline implementation: 4 tests ran, 3 failed. Declared/repeated
  oversized bodies reached normal routing instead of 413, and the never-completing chunked body
  timed out. The exact-boundary request already passed.
- First filter diagnostic run: declared, boundary and repeated cases passed, while chunked returned
  500 because advice treated the private body exception as generic. The controlled publisher test
  then proved server cancellation/release independently of Reactor Netty's client publisher.
- Codec-seam RED: `IngressBodyLimitWebFilterTest` failed compilation with
  `ServerCodecConfigurer cannot be converted to long`. The production constructor now resolves the
  matching JSON decoder's configured finite limit.
- Focused GREEN:
  `./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimatorTest'
  --tests 'it.unimib.datai.nanofaas.controlplane.api.IngressBodyLimitWebFilterTest' --tests
  'it.unimib.datai.nanofaas.controlplane.api.IngressBodyLimitHttpTest' --console=plain --offline`
  → **BUILD SUCCESSFUL** in 8s, 16 tests (8 estimator, 3 controlled filter, 5 real HTTP).
- Integration GREEN: `./gradlew test --no-parallel --continue --console=plain --offline` →
  **BUILD SUCCESSFUL** in 2m21s (190 actionable tasks: 25 executed, 165 up-to-date).

**Status of claims:** implemented and focused-test verified for body limiting and retained-input
measurement; integration-verified through a real Reactor Netty server for declared/chunked 413,
boundary acceptance, ordering, repetition and subsequent-request recovery, plus the complete
repository suite. No soak, container or Kubernetes E2E was run for P07b.

**Residual transition:** until P07e publishes a calibrated NanoFaaS ingress setting, the pre-parser
filter intentionally shares the active Spring JSON decoder limit. P07e may replace the constructor
resolution with validated NanoFaaS configuration, but it must configure decoder and streaming
filter consistently so neither boundary silently exceeds the other.

### P07b fix round 1/5 — reject hidden collection capacity

Review found that accepting every `java.util` list/map while charging only logical `size()` let
spare `ArrayList` capacity and retained `HashMap` tables bypass conservative byte accounting.
Package identity cannot expose backing capacity, and `java.util` wrappers can also delegate to
arbitrary collection code. The estimator now rejects every `List` and `Map` before invoking
collection methods. Scalars and explicit arrays remain supported; a caller converting a
collection must retain only the bounded array representation it measures.

TDD regression file:
`platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/input/RetainedInputEstimatorTest.java`.
The RED run executed 10 tests and failed exactly the two new assertions: a one-element
`ArrayList` with capacity 1,000,000 and an emptied `HashMap` with an allocated 1,000,000-entry
table were incorrectly returned as `Measured`. The focused GREEN command
`./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimatorTest' --console=plain --offline`
completed with `BUILD SUCCESSFUL in 3s` (78 actionable tasks: 7 executed, 71 up-to-date).
The full repository suite `./gradlew test --no-parallel --continue --console=plain --offline`
completed with `BUILD SUCCESSFUL in 2m 22s` (190 actionable tasks: 20 executed,
170 up-to-date).
The full repository suite `./gradlew test --no-parallel --continue --console=plain --offline`
completed with `BUILD SUCCESSFUL in 2m 22s` (190 actionable tasks: 20 executed,
170 up-to-date).

## P07c — Aggregate execution and retained-input quotas

**Task boundary:** P07c on top of `0ada8297`. This gate integrates finite global and
per-function logical-execution and retained-input quotas across direct, queued, retry and offload
paths. It deliberately does not add waiter quotas, public quota properties, Helm values or final
calibration; those remain P07d/P07e.

### Implemented

- New executions canonicalize JSON `ArrayList`/`LinkedHashMap` and JDK immutable JSON collections
  into a bounded tagged `Object[]` tree before record publication. Depth, logical width, visited
  nodes, retained bytes and allocation arithmetic are bounded. Successful records retain only the
  measured canonical tree; arbitrary collection implementations are rejected without invoking
  their methods, and opaque LOCAL values remain rejected unless they are accepted scalar/array
  representations.
- `InvocationCapacity` reserves logical execution count and canonical input bytes as distinct
  `ResourceQuota` reservations in one rollback batch, fenced by the existing
  `FunctionGeneration`. Global and same-name limits include old draining generations. Saturation
  remains on the existing 429/`Retry-After` overload path and is distinct from ingress 413.
- Logical execution ownership ends at the one terminal settlement. Canonical input has an
  independent bounded reference-counted owner: queue entries carry an idempotent reference,
  physical LOCAL/HTTP/deployment/offload attempts retain another, and terminal/administrative
  settlement releases only the record's base reference. The last real queue/attempt callback
  drains the canonical reservation.
- Materializing canonical collection input for a runtime creates a separate, same-generation
  `INPUT_COPY` reservation before allocation and releases it only from the raw physical future.
  Retries reuse the canonical representation and do not reserve another logical execution;
  existing-key replay reserves neither execution nor input. Existing `DispatchLease` and
  generation identity are unchanged.
- Record publication, enqueue refusal/throw, dispatch submission failure, offload synchronous
  failure, queue timeout/removal and scheduler dispatch failure have explicit idempotent cleanup.
  Internal finite safety ceilings ensure optional-module-free production paths cannot bypass the
  accounting; P07e still owns public configuration and calibrated defaults.

### Focused-test verified

- Canonicalization covers nested/flat, deep, wide and large inputs, source non-retention, accepted
  arrays, opaque/custom collection non-traversal, custom numeric objects and repeated rejection.
- Quota tests cover cross-function global saturation, per-function saturation across generations,
  atomic two-dimensional rollback, bounded reference counts, barrier-controlled concurrent
  admission, retry sharing and physical drain without sleeps.
- Lifecycle integration covers publication and enqueue rollback, replay/retry reuse, 1,000 stable
  repeated input refusals, old/new generation coexistence, non-cooperative LOCAL work, pending
  offload, synchronous offload failure, and administrative terminal state while a removed-store
  queue entry still retains input.
- Final focused core/async-queue/sync-queue/offload/profile matrix completed with
  **BUILD SUCCESSFUL in 43s** (90 actionable tasks: 20 executed, 70 up-to-date).

### Integration-verified

- The selected non-cartesian matrix exercised direct core dispatch; async-queue SYNC/ASYNC,
  retry, context and scheduler-failure contracts; sync-queue activation, lifecycle, retry,
  timeout/removal and scheduler-failure contracts; eager/pressure offload plus hop/header behavior;
  core bean/profile startup; and the unchanged ingress body-limit boundary.
- Full repository suite: `./gradlew test --console=plain --offline` →
  **BUILD SUCCESSFUL in 2m22s** (190 actionable tasks: 22 executed, 168 up-to-date).

**Status of claims:** implemented, focused-test verified and repository-integration verified for
P07c. These quotas conservatively account retained Java representations; they do not claim to bound
RSS, native/client buffers or remote memory. No soak, container or Kubernetes E2E was run. Public
defaults/calibration and waiter ownership remain explicitly deferred.

## P07c — Fix Round 1

**Implemented**

- Separated administrative/logical cancellation from raw deployment and offload drain. Physical
  input and dispatch capacity now close only when readiness/transport/remote work actually drains.
- Propagated direct physical-copy saturation through the existing overload boundary. Async and
  sync schedulers now retain a bounded queue-slot reservation while attempting dispatch and
  requeue on input-copy backpressure, including concurrent-admission and removal races.
- Extended synchronous rollback to `Error` for aggregate admission, dispatch submission, retry
  scheduling, and executor-backed retry submission.
- Preserved the admission's existing `FunctionGeneration` on records, including offload and
  retries, so late old-generation success/failure callbacks cannot update replacement metrics.

**Focused-test verified**

- Controlled regressions cover cancellation before deployment wake-up, non-cooperative transport,
  canceled offload, direct and queued physical-copy saturation above half the input quota,
  async/sync queue displacement, admission/dispatch/retry `Error`, and late old-generation
  offload success/failure. No sleep is used as ordering proof.
- Final focused consumer/profile matrix: `BUILD SUCCESSFUL` in 43s; 86 actionable tasks
  (6 executed, 80 up-to-date).

**Integration verified**

- Final repository suite: `./gradlew test --console=plain --offline` — `BUILD SUCCESSFUL` in
  3m13s; 190 actionable tasks (18 executed, 172 up-to-date).
- GitNexus exact-checkout detect-changes and explicit staging audit are recorded in the ignored
  P07c task report. Controller staging/commit remains external because this sandbox cannot write
  Git metadata.

## P07d — Bounded waiter admission and isolated detach

P07d adds a distinct generation-scoped waiter owner with finite internal global and per-function
safety ceilings; P07e still owns calibrated public values and runtime reduction. Every synchronous
caller, including pending and archived idempotency replay, reserves one waiter before its result
attachment is returned. Saturation follows the existing overload/`Retry-After` boundary and never
charges execution or canonical input twice.

The waiter handle is detached exactly once by terminal delivery, its own timeout, cancellation or
client disconnect. Detach never cancels the shared completion, queue entry, dispatch, retry, input
owner or another waiter. A bounded retained-owner registry supports shutdown draining and fences
post-close admission; reservations remain attributed to their acquiring `FunctionGeneration`, so
late old-generation signals cannot release replacement accounting.

Controlled tests cover global and per-function saturation, 100 rejected replays of one pending key
without redispatch or retained-state growth, direct/no-queue plus representative async/sync queue
paths, divergent deadlines, archived replay, enqueue publication failure, disconnect, duplicate
completion/cancellation, shutdown and remove/re-register races. These waiter counters bound only
the retained control-plane waiter owners; they do not claim to bound RSS, HTTP/native buffers or
remote memory.

Validation on 2026-09-09 is green: the focused waiter and HIGH-impact consumer matrix completed in
17 seconds (`87 actionable tasks: 15 executed, 72 up-to-date`), and `./gradlew test --offline`
completed in 3 minutes 10 seconds (`190 actionable tasks: 21 executed, 169 up-to-date`). GitNexus
1.6.11 `detect-changes --scope all --limit 10000` completed successfully before staging and reported
12 indexed changed symbols, 46 affected processes and `critical` aggregate risk; a staged rerun is
recorded in the P07d task report so newly tracked P07d files are included in the exact commit view.

## P07d — Fix Round 1

The disabled compatibility path has been removed. `ReactiveInvocationCoordinator` now has only the
constructor that requires `WaiterCapacity`, and the public `InvocationService` constructor which
created a coordinator without that dependency has been removed. Direct test and module callers now
construct an explicit finite waiter authority sharing the logical admission generation registry.
Legacy records without logical admission metadata still reserve against the currently active
generation; this preserves compatibility without bypassing either finite limit.

The pending replay-storm regression now snapshots execution owners, retained input bytes, live
records and archived outcomes. Each of 100 waiter refusals must leave all four values unchanged;
after terminal drain it proves zero waiter/execution/input/live-record retention and exactly one
expected keyed archived outcome. Duplicate terminal coverage now invokes
`ExecutionCompletionHandler.completeExecution` twice with distinct results, rather than attempting
to complete the same `CompletableFuture` twice.

TDD produced a behavioral RED for the public-constructor bypass and a second RED which exposed use
of the archived-outcome counter where the live-record counter was intended. The corrected focused
P07d tests completed successfully in 5 seconds (78 actionable tasks), the full impacted constructor
and module matrix completed successfully in 18 seconds (87 actionable tasks), and the full repository
suite completed successfully in 2 minutes 22 seconds (190 actionable tasks: 22 executed,
168 up-to-date). GitNexus impact and final detect-changes evidence are recorded in the P07d report.

Final pre-commit review found no additional defect. `git diff --check` and the exact 23-file staged
audit completed without findings. GitNexus 1.6.11
`detect-changes --scope all --limit 10000` exited 0 after staging with 25 changed files, 60 indexed
changed symbols, 42 affected processes and `critical` aggregate risk. It emitted neither
`PARTIAL RESULT` nor `LISTING CAPPED`; the count comprises the 23 staged P07d files plus two
unrelated tracked overload experiment files which remain unstaged. The GitNexus index remains
stale at `bdada6c` with a recorded `full-rebuild` in progress, so exact text-search caller audits
and the green impacted test matrix remain the supplementary evidence for symbols added after that
commit.

## P07e — Public finite limits, calibration and integrated P07 gate

**Scope and configuration.** P07e publishes one validated
`nanofaas.invocation-capacity` group for the P07 owners. The packaged application, generated
Spring property metadata, Helm values/template/schema and control-plane documentation use the
same defaults:

| Limit | Global | Per function |
|---|---:|---:|
| Logical executions | 4,096 | 512 |
| Canonical retained input | 134,217,728 B (128 MiB) | 33,554,432 B (32 MiB) |
| Physical input copies | 67,108,864 B (64 MiB) | 16,777,216 B (16 MiB) |
| Attached waiters | 8,192 | 1,024 |

The ingress body and per-execution retained-input limits are both 1,048,576 B. Canonicalization is
also bounded by 64 retained references, depth 32, 16,384 entries per container and 65,536 visited
nodes. Startup rejects non-positive, overflowing or inconsistent values, including a per-function
limit above its global limit and a per-execution input limit above either per-function byte limit.
The first JSON reader WebFlux will actually use for `InvocationRequest` must expose the configured
finite codec limit; a mismatched or unsupported first applicable custom reader aborts startup.

Runtime-config accepts partial quota patches. Validation uses the merged effective snapshot.
Reducing any limit below occupancy preserves every current owner and generation; it only refuses
new reservations until the corresponding usage drains. Increasing a limit changes admission
without transferring existing ownership. Controlled coverage holds execution, canonical input,
physical-copy and waiter owners across a reduction, observes refusals, drains them to zero and
then observes admission resume.

**Default-sizing envelope.** The minimum chart control-plane request is 512 MiB
(536,870,912 B). The explicit byte envelope is 128 MiB canonical input + 64 MiB physical copies +
11,600,000 B (about 11.06 MiB) derived outcome budget (`100000 * 116 B`), or about 203.06 MiB.
Conservative planning allowances add about 32 MiB for 4,096 logical owners at 8 KiB each, 16 MiB
for 8,192 waiters at 2 KiB each, and 12.2 MiB for 100,000 key/tombstone owners at about 128 B each.
That leaves about 248.7 MiB of the 512 MiB request for JVM/runtime structures, parsing, HTTP,
threads and native overhead. The count-owner sizes are planning assumptions, not object-layout
measurements. The 1 MiB ingress bound, HTTP pool (500 connections, at most 1,000 derived pending
acquires) and transport timeouts independently constrain transient work. Remote-runtime memory,
kernel/socket buffers, allocator fragmentation and transient parser/native buffers are excluded;
this calibration does not claim an aggregate RSS bound. Per-function caps prevent one function
from consuming the global envelope.

**T1/T2 calibration.** Reproducible source is `P07Calibration.java`. Both revisions used OpenJDK
25.0.4 on Linux aarch64, 20 available processors, `-Xms256m -Xmx256m`, 20,000 warm-up operations
and a 2,000 ms T1 measurement. P00 was checked out read-only from `61d72e73`; P07e was measured on
the current tree above `01b61870`.

| Corpus | Revision | Offered / admitted | Throughput | p50 / p95 | Allocated/op | Drain / retained outcome |
|---|---|---:|---:|---:|---:|---|
| T1 SYNC unkeyed | P00 | 752,511 / 752,511 | 376,249.7/s | 1.600 / 7.024 us | 870.9 B | live=0; outcomes=120,833 |
| T1 SYNC unkeyed | P07e | 488,461 / 488,461 | 244,226.4/s | 3.248 / 9.984 us | 1,997.4 B | live=0; outcomes=54,716 |

The ordinary-path checkpoint is unfavorable: throughput is 35.1% lower, p50 is 103.0% higher,
p95 is 42.1% higher and measured allocation is 129.4% higher. This is a disclosed P07e concern,
not a failed correctness gate; P19 remains the owner of the comparative performance decision.

| T2 ASYNC/keyed shape | Offered / admitted | P07e canonical bytes | P00/P07e outcomes | P00/P07e live | Redispatch after eviction |
|---|---:|---:|---:|---:|---:|
| flat | 200 / 200 | 220 | 1 / 0 | 0 / 0 | 0 / 0 |
| deep | 200 / 200 | 2,168 | 1 / 0 | 0 / 0 | 0 / 0 |
| wide | 200 / 200 | 131,170 | 1 / 0 | 0 / 0 | 0 / 0 |
| large | 200 / 200 | 524,328 | 1 / 0 | 0 / 0 | 0 / 0 |

P00 has no canonical-byte instrument (`not-available-on-P00`). Replaying key 0 only after all 200
terminal admissions proves that outcome eviction leaves the key tombstone and never redispatches.
All live execution owners drain; the intentionally tiny T2 outcome budget explains retained
outcome counts of zero/one and is not the production default.

**Integrated non-cartesian P07 matrix.** One shared core gate covers quota primitives, codec/413,
canonicalization, direct admission, replay/retry, short/long waiter detach, publication/enqueue
failure, remove/re-register generation fencing and stop/drain. Async-queue tests add SYNC/ASYNC,
retry, scheduler error and queue-state paths; sync-queue adds dispatch backpressure and runtime
lifecycle; offload adds slow/pending offload and waiter budgeting; runtime-config adds reduction
below occupancy and rollback. This set crosses every changed consumer without multiplying modes
whose ownership transitions are identical. T3 is represented by replay plus divergent waiter
deadlines; T4 by retry, slow/non-cooperative and offloaded physical work; T9 by publish/enqueue
errors, function replacement and shutdown drain. Assertions independently return logical,
canonical, physical-copy and waiter counters to zero and preserve old-generation visibility until
physical drain.

Focused RED/GREEN evidence included missing-symbol compile REDs for the new public property,
runtime-limit and active-codec behavior, followed by green focused core and runtime-config tests.
The selected integrated command spanning core, async-queue, sync-queue, offload and runtime-config
completed with no failed/error test reports. Separate `bootJar` commands for `none`, `async-queue`,
`sync-queue,runtime-config`, `container-deployment-provider` and `all` all completed successfully.
`helm lint deploy/helm/nanofaas` reported zero failed charts and `helm template` rendered every
configured environment value. The first whole-repository run exposed missing property wiring in
WebFlux test slices (65 context failures); the first attempted repair removed those failures but
introduced a forbidden `capacity -> api` dependency caught by two ArchUnit failures. The final
component wiring keeps production property alignment, permits intentionally partial WebFlux test
contexts to use their active codec limit and introduces no package cycle. Its focused 77-test
WebFlux/HTTP/codec/architecture gate passed, then the post-fix whole-repository suite completed
with **BUILD SUCCESSFUL in 2m19s** (190 actionable tasks: 18 executed, 172 up-to-date). Exact
GitNexus change analysis is recorded in the task report.

**GitNexus pre-edit evidence.** `InvocationCapacity` was HIGH risk (16 impacted symbols, seven
direct callers, three processes and two modules) and was reported before editing. `ResourceQuota`
and the named codec helper were LOW. Spring-created configuration/filter/runtime extension symbols
were UNKNOWN or absent from the three-commit-stale index; exhaustive text searches identified the
Spring bean wiring, service/module consumers and tests before edits. UNKNOWN was not treated as a
clean result. No P11+ policy, soak, container or Kubernetes E2E work is claimed here.

## P07e — Fix Round 1: configured HTTP evidence and quota wire contract

The acceptance evidence now comes from `P07ConfiguredHttpCalibrationTest`, which boots the real
Spring application twice and sends invocation traffic through Reactor Netty, WebFlux codecs, the
controller, invocation services and capacity owners. T1 uses the core-only `none` profile and T2
uses the published `async-queue` profile, including the real scheduler and bounded per-function
queue. Only the downstream LOCAL function boundary is controlled so retained populations can be
sampled before a deterministic drain. The earlier `P07Calibration.java` factory/store
microbenchmark remains solely to disclose the original P00/P07e regression. Because it bypasses
the configured application, HTTP stack and queue modules, its figures are explicitly
non-comparable with this acceptance run; the unfavorable historical regression remains open for
P19 and is not replaced with invented comparative evidence.

Both runs used Java 25.0.4 on Linux aarch64 and the published P07 capacity defaults. Allocation is
the increase in allocated bytes reported by `ThreadMXBean` for JVM threads alive at each sample;
it is a whole-process observational delta, not retained heap and not a cross-run benchmark.
Latency is HTTP round-trip latency measured at the test client. No performance threshold is
asserted.

| Corpus / shape | Offered / admitted | Quota refusals | p50 / p95 HTTP | Observed allocation | Retained peak / drain |
|---|---:|---:|---:|---:|---|
| T1 SYNC unkeyed, `none` | 1,000 / 1,000 | 0 | 2,910.829 / 5,671.130 us | 152,534,936 B | exec/live/waiter 1; all live owners 0 after drain |
| T2 flat, `async-queue` | 100 / 100 | 0 | 5,736.499 / 10,367.001 us | 130,940,880 B | exec/live 100; input 22,000 B; all live owners 0 after drain |
| T2 deep, `async-queue` | 100 / 100 | 0 | 3,274.377 / 5,331.524 us | 15,932,576 B | exec/live 100; input 216,800 B; all live owners 0 after drain |
| T2 wide, `async-queue` | 100 / 100 | 0 | 5,451.044 / 9,148.283 us | 41,515,856 B | exec/live 100; input 13,117,000 B; all live owners 0 after drain |
| T2 large, `async-queue` | 100 / 63 | 37 input | 7,480.639 / 10,987.111 us | 305,548,800 B | input 33,032,664 B at the 32 MiB/function boundary; all live owners 0 after drain |

Every T2 replay of key 0 after drain returned either the retained terminal result or 410 after
outcome loss. Dispatch count remained unchanged in all four cases, proving that outcome eviction
or retention refusal preserves the key tombstone and never re-executes the function.

The documented admission sequence now matches runtime behavior. For new synchronous work the
factory claims the key, canonicalizes input, reserves execution/canonical bytes and publishes the
live record before waiter admission. A waiter refusal calls `abandonAdmission`, removing the new
record and rolling back key, execution and input ownership. Replay skips those new owners and
reserves only its transient waiter. Characterization tests independently cover both sequences.

Invocation quota overload is mapped before its `QueueFullException` superclass for both sync and
async endpoints. The stable 429 response carries `Retry-After: 1` and
`{"error":"invocation_quota_exceeded","resource":"execution|input|input_copy|waiter"}`.
Controller tests cover all four resources on both endpoints; real HTTP saturation covers sync and
async execution, aggregate canonical input, and pending-replay waiter refusal. The focused gate
and both profile calibrations are green. The corrected integrated core + async-queue + sync-queue
and runtime-config gate is green in 36 seconds (89 actionable tasks: 13 executed, 76 up-to-date).
The single fix-round full repository run, `./gradlew test --console=plain --offline`, is also green
in 2 minutes 9 seconds (190 actionable tasks: 23 executed, 167 up-to-date).

## P11 — Shared wake-up, cancellable timers and warm fast path

`DeploymentWakeUpGate` now has one wake-up owner per exact P07 function generation and gives every
caller an independent future. Cancelling or timing out one caller therefore cannot complete the
shared owner or the other callers. The owner retains its absolute timeout, current poll and
scale-protection lease handles; its single idempotent cleanup path cancels all three on success,
failure, removal or shutdown. `DeploymentWakeUpCoordinator` uses the same generation authority and
scheduler, retains only a bounded lock state per active generation, and removes that state on
function removal or context close. It refuses post-close scale-down work, so shutdown cannot
recreate coordinator state.

The already-ready path consumes P10's immutable `ReplicaObservation`. Only an available, `FRESH`,
ready observation whose timestamp is within `nanofaas.deployment.wakeup.ready-observation-max-age`
bypasses the gate. The packaged default is 5 seconds, matching the snapshot TTL. Missing, expired,
`STALE`, `UNAVAILABLE` and fresh-but-unready observations enter the forced-fresh gate. Generation
and registration are revalidated after observation, fencing a concurrent detach. A fresh read with
desired replicas above zero waits for readiness without writing `1`, so desired 10 / ready 0 is
never reduced.

The shared wake-up scheduler is a `ScheduledThreadPoolExecutor` with remove-on-cancel enabled and
delayed/periodic work disabled after shutdown. Deterministic tests use controlled clocks, queued
futures and latches. The 37 focused gate/coordinator tests are green in 14 seconds and prove 2,000
warm calls create no forced GETs or queued timers, 100 callers share one owner while a short caller
detaches independently, all freshness states route correctly, synchronous/async failures and the
absolute deadline return owner/timer counts to zero, and removal/re-registration/shutdown fence late
work. The final full offline repository suite is green in 2 minutes 29 seconds (190 actionable
tasks: 18 executed, 172 up-to-date). Separate `bootJar` builds for `none`, `async-queue`,
`sync-queue,runtime-config`, `container-deployment-provider` and `all` are also green.

Pre-edit GitNexus analysis reported LOW risk for `DeploymentWakeUpGate`, MEDIUM for
`DeploymentWakeUpCoordinator`, and LOW for their disambiguated existing methods; there were no
HIGH/CRITICAL results. UNKNOWN constructors, configuration/property binding and
`isScaleDownProtected` were resolved with exact text searches before editing. The P10 refresh-pool
cancellation note remains outside P11 and was not changed; async-queue invoke/enqueue behavior and
P12+ scope are untouched.

### P11 fix round 1 — generation-fenced mutation and physical drain

Replica mutation now has a generation-aware path that acquires the shared function-operation lock
and validates both the exact P07 generation and managed target before changing the registry or
provider. The wake-up gate uses that path. `InternalScaler` captures the generation only while its
observed `RegisteredFunction` is still the current registry object, carries it through evaluation,
and uses it for both scale-up and scale-down. The wake-up coordinator no longer has a name-only
downscale branch: a stale or absent expected generation cannot execute the mutation callback.

Gate and coordinator removal/close now retire owners and cancel queued timers immediately while
retaining generation attribution until already-submitted synchronous callbacks exit. Package-visible
owner counts deliberately expose this physical drain in tests; no Actuator metric was added. Poll
publication also handles a scheduler executing the callback before `schedule()` returns, without
allowing the predecessor to cancel the successor. Static fallback schedulers, no-argument wake-up
coordinators and implicit standalone generation creation were removed; tests now supply and close
their own generation authorities and removable schedulers.

Deterministic coverage uses latches, controlled nanotime, raw captured expiry callbacks, executor
completion barriers and a pre-return scheduler; the coordinator tests contain no park/sleep ordering.
The final focused core suite (gate, wake-up coordinator and managed deployment coordinator) is green
in 6 seconds, and all `InternalScaler*Test` cases are green in 4 seconds. Packaging is green for
`none`, `async-queue`, `sync-queue,runtime-config`, `container-deployment-provider` and `all`. After
correcting two test-harness synchronization defects exposed by integration, the final full offline
repository suite completed with **BUILD SUCCESSFUL in 2m32s** (190 actionable tasks: 26 executed,
164 up-to-date).

### P11 fix round 2 — atomic provenance and owner publication

The wake-up gate now obtains its generation through the managed deployment coordinator's atomic
registry-object check. A remove/re-register between the gate's registry lookup and generation
capture therefore cannot combine an old `RegisteredFunction` or target with the replacement
generation. Gate owner publication and coordinator state publication are each serialized with
their corresponding remove/close scan. Publication linearizes before lifecycle retirement and is
observed by that retirement, or lifecycle wins and no owner/state can appear after it returns.
Logical cancellation remains immediate while already-submitted callbacks keep their exact
generation attribution until physical drain.

Deterministic regressions pause the exact registry lookup/capture gap and the real map publication
operations. They use latches plus an explicit monitor-blocked barrier, then release publication and
assert caller failure, empty timer queues and zero physically owned gate/coordinator entries after
the captured callback drains. The stale generation-aware replica mutation test now observes the
writer waiting inside `FunctionOperationLocks.withLock` by thread state and stack frame before
releasing the lifecycle lock; its ordering proof no longer uses a 100 ms `Future.get` timeout.

The covering gate/coordinator/managed-coordinator and `InternalScaler*Test` suites are green in 10
seconds. `bootJar` is green for `none`, `async-queue`, `sync-queue,runtime-config`,
`container-deployment-provider` and `all`. The one full offline repository suite completed with
**BUILD SUCCESSFUL in 2m33s** (190 actionable tasks: 20 executed, 170 up-to-date).

### P11 fix round 3 — lasting coordinator removal fence

Coordinator removal now records the exact active generation under the same lifecycle monitor used
by every state-publication path. While capacity removal is still pending, both scale-up lease
publication and autoscaler downscale publication reject that generation even after the original
state has physically drained. Fences are pruned when capacity ownership advances, so a distinct
replacement generation is admitted while historical generations do not accumulate.

The deterministic regression pauses an old-generation caller before coordinator admission, lets
coordinator removal return while that generation remains active, and proves both its scale-up and
downscale callbacks are rejected. It then removes and re-registers capacity, verifies the new
generation publishes a lease successfully, and drains its timer and state. Focused coordinator,
gate, managed-coordinator and autoscaler tests are green in 9 seconds; all five packaging profiles
are green; the full offline suite is green in 2 minutes 33 seconds (190 actionable tasks: 20
executed, 170 up-to-date).

### P11 fix round 4 — failed-removal rollback fence restoration

Removal rollback now reopens only the exact P07 generation that is still current when the gate's
registration callback runs. The coordinator performs that check and tombstone removal under its
publication/removal lifecycle monitor. This repairs listener order `[gate, failure, capacity]`,
where capacity never retired the original generation, without reviving an old generation after a
successful remove/re-register. Logical retirement and bounded physical drain are unchanged.

A deterministic real-`FunctionService` regression drives that listener order, verifies the
original generation and restored catalog entry, manually advances the forced wake-up poll, and
asserts successful readiness, released scale-down protection, zero gate owners, and an empty timer
queue. A subsequent removal continues to reject old-generation scale-down. The final focused
core/autoscaler suite is green in 13 seconds (81 tasks), all five packaging profiles are green, and
the full offline repository suite is green in 3 minutes 9 seconds (190 actionable tasks: 22
executed, 168 up-to-date).

## P12 — Finite-lifetime HTTP pools and aggregate ownership

The resolved HTTP transport is Reactor Netty **1.3.6** (Gradle
`dependencyInsight`, selected by Spring Boot 4.1.0). Its official Maven Central source artifact,
`reactor-netty-core-1.3.6-sources.jar` (SHA-256
`061136ccc1bc3bed6938cea77a7343dc47de275aad4851b3407fb7ac15854121`), is the API and semantic
authority used for this change. `ConnectionProvider.java` states that a pool corresponds to one
concrete remote host (lines 383–389), documents `pendingAcquireTimeout` (575–585),
`maxIdleTime` (632–645), `maxLifeTime` (647–659), default idle/lifetime eviction (693–708), custom
metric registration (739–752), background eviction and its shared Reactor scheduler default
(783–820), and disposal of empty inactive pools (436–453). The same source explicitly says
`maxConnectionPools` only logs a warning after the expected count is exceeded (492–507), so it is
not exposed as an aggregate cap. `ConnectionPoolMetrics.java` defines acquired, allocated, idle and
pending counts (20–55). `PooledConnectionProvider.java` confirms one map entry per destination,
metric register/deregister on pool create/remove, map clearing during `disposeLater`, and inactive
pool checks scheduled on shared `Schedulers.parallel()`. The exact 1.3.6 implementation schedules
that callback at lines 422–426 and unconditionally schedules it again at line 466, even after
provider disposal. P12 therefore no longer enables that helper. Its single owner-cancellable task
uses the supported `ConnectionProvider.disposeWhen(SocketAddress)` API (interface lines 204–213;
implementation lines 248–278).

One context-owned `DispatchConnectionPool` now builds and reuses the provider, exports only its
facade, aggregates destination/allocated/active/idle/pending gauges without destination tags, and
performs cached idempotent disposal. New acquires are rejected once disposal begins. The packaged
policy is 30 s maximum idle time, disabled maximum lifetime (`0`, applied conditionally), 5 s idle
eviction checks, and empty-pool checks every 5 s after 30 s inactivity. The existing 500
connections-per-destination, derived 1,000 pending-per-destination and **45 s** acquisition timeout
remain unchanged. These are transport retention controls; P07 remains the aggregate execution,
retained-input and waiter admission owner across all destinations. The pool owner has exactly one
daemon `ScheduledThreadPoolExecutor` thread and one fixed-delay task. Remove-on-cancel is enabled;
close cancels and removes the task, shuts down the executor and waits for observable termination,
leaving no recurring callback on Reactor's shared scheduler. Real `FunctionService`
registration/removal events retire an endpoint pool after its last function owner disappears, and
re-registration can immediately use a replacement host. All five aggregate meter handles are
removed exactly once during shutdown, so a reused registry cannot retain a stale owner.

Deterministic controlled-server tests use latches, wire request counts, connection sequence numbers,
pool metrics and explicit Awaitility deadlines; the P12 ordering tests contain no sleeps. The
measured test policy (100–150 ms idle/lifetime, 20–50 ms checks and 200 ms inactivity) physically
rotates expired sockets and reduces 16 historical destinations/connections to at most one within a
5 s deadline. Cancellation, 150 ms acquisition timeout, 1 s function timeout, a drained 16 KiB
HTTP error, endpoint replacement/removal, repeated context/owner close and post-close acquisition
all reach zero pending/active ownership with the asserted socket behavior. A real P07 path proves a
second host cannot bypass global execution, input or waiter admission.

The final focused set is green: 46 tests in `DispatchConnectionPoolTest`, `HttpClientPoolTest`,
`HttpClientPropertiesTest`, `P12CrossHostAdmissionTest`, `DispatchLifecycleAndCancellationTest`,
`ExternalDispatcherTest` and `ExternalDispatcherTimeoutTest` (`BUILD SUCCESSFUL in 17s`). Separate
`bootJar` builds are green for `none`, `async-queue`, `sync-queue,runtime-config`,
`container-deployment-provider` and `all`. The full repository `./gradlew test` suite is green in
2 minutes 33 seconds (190 actionable tasks: 18 executed, 172 up-to-date).

Pre-edit GitNexus impact reported no HIGH/CRITICAL symbols. The disambiguated `HttpClientProperties`
record, `HttpClientConfig.dispatchConnectionProvider#1` and `HttpClientConfig.webClient#3` results
were UNKNOWN; exact constructor/type and direct-call searches resolved them before editing (Spring
bean calls are reflective). The final complete staged/all detection and staged diff audit are
recorded in the P12 task report.

### P12 review fix round 1

All four review findings have deterministic regressions. The scheduler test observes exactly one
recurring queue entry, remove-on-cancel, idempotent close, a cancelled future, an empty queue and
executor termination. The endpoint test drives actual `FunctionService.register`, `remove` and
re-register operations: the old pending dispatch fails without reaching the wire, the old pool
drains, and the replacement endpoint succeeds. A same-registry close/recreate test proves all five
aggregate gauges disappear and return with live zero-valued targets. An isolated Netty 4.2.15
`UnpooledByteBufAllocator` provides independent byte accounting: after eight bounded 16 KiB error
responses, active cancellation and acquisition timeout, heap-plus-direct allocation returns to the
exact pre-test baseline. This changes no P13 buffering/deadline policy.

The focused pool/P07 suite is green in 19 seconds. All five packaging profiles are green. The full
offline serial repository suite is green in 2 minutes 57 seconds (190 actionable tasks: 21
executed, 169 up-to-date). The per-destination budget and 45-second acquisition timeout remain
unchanged, and P07 aggregate admission remains independently green across hosts.


## P13 — Bounded container-proxy buffers and phase deadlines

Starting revision: `30751213a9032c9b1c278545f51155a71e7e8edd` on
`control-plane-lifecycle-memory`.

The container-local proxy now reads request and backend response bodies through a capacity-accounted
bounded buffer. Actual bytes are checked independently of `Content-Length`, including raw chunked
requests. The per-proxy aggregate owner accounts for backing-array capacity and temporary resize overlap;
it is separate from, and does not replace or duplicate, P07's platform execution/input/waiter owners.
The P12 `HttpClient` remains the sole outbound connection owner. Request overflow is `413`,
response overflow is a cancelled backend body plus `502`, and aggregate exhaustion is `503`.
Request bytes are released after the outbound body has been sent; response bytes are released after
the caller write or any error.

One owner-scoped, remove-on-cancel `ScheduledThreadPoolExecutor` supplies sequential inbound-read,
function-configured backend, and response-write deadlines. Its queue has at most one current phase
deadline per admitted invocation, hence remains bounded by `maxInFlight`; `close()` stops admission,
closes active exchanges, interrupts handlers, shuts down the deadline owner, and closes the existing
P12 client. `/health` does not consume count or byte admission.

Selected production policy: 2 MiB request, 4 MiB response, 32 MiB aggregate, 5 s inbound-read and
5 s response-write deadlines; backend deadline remains each function's `timeoutMs`. The 2 MiB
request cap provides envelope/escaping headroom over P07's 1 MiB ingress cap. Repository payload
measurement found the largest individual canonical performance input is 440,571 bytes
(`word-stats/performance-large.json`), with JSON Transform next at 412,979 bytes. A warm real-proxy
1 KiB loop measured 200 calls at 3,390.4 us/call and 295.0 calls/s on this host. Those values fit the
bounded policy, so P13 retains bounded integral buffering and does not introduce streaming.

Strict TDD evidence:

- Initial RED:
  `./gradlew :control-plane-modules:container-deployment-provider:test --tests '*P13BoundedProxyTest' --tests '*P13ProxyConfigurationTest' --console=plain --offline`
  failed at `compileTestJava` with 17 expected missing-symbol errors for
  `ContainerProxyProperties`, the configured constructor and `snapshot()`.
- Cleanup RED:
  `./gradlew :control-plane-modules:container-deployment-provider:test --tests '*P13BoundedProxyTest.invalidBackendUriAfterReadingBodyStillReleasesTheRequestBuffer' --console=plain --offline`
  failed because the observed snapshot retained the request buffer after URI construction failed.
- Growth RED:
  `./gradlew :control-plane-modules:container-deployment-provider:test --tests '*P13BoundedProxyTest.aggregateBudgetSmallerThanGrowthChunkStillAcceptsARepresentableBody' --console=plain --offline`
  failed at line 178 because a fixed 8 KiB growth reservation rejected a 3-byte body under a
  representable 7-byte aggregate budget.
- Focused GREEN after the corresponding minimal changes:
  the P13/configuration pair succeeded in 11 s; the cleanup case succeeded in 4 s; and the aggregate
  growth plus close-race pair succeeded in 4 s.
- Deadline separation RED/GREEN:
  `./gradlew :control-plane-modules:container-deployment-provider:test --tests '*P13BoundedProxyTest.callerWriteGetsItsOwnDeadlineAfterTheBackendBodyHasCompleted' --rerun-tasks --console=plain --offline`
  failed against the restored regression because the caller write was cut off 0.409 s after backend
  completion rather than receiving its independent 2 s deadline; after moving caller writing outside
  the backend-read deadline scope, the same command succeeded in 8 s.
- Existing plus new proxy suite:
  `./gradlew :control-plane-modules:container-deployment-provider:test --tests '*RoundRobinFunctionProxyTest' --tests '*P13BoundedProxyTest' --tests '*P13ProxyConfigurationTest' --console=plain --offline`
  succeeded in 8 s.
- Complete provider integration:
  `./gradlew :control-plane-modules:container-deployment-provider:test --rerun-tasks --no-parallel --console=plain --offline`
  succeeded after the final correction in 20 s (39 actionable tasks, all executed).
- Packaging:
  `./gradlew :control-plane:bootJar :control-plane:processAot --no-parallel --console=plain --offline`
  succeeded (JVM boot JAR; AOT intentionally skipped by the non-native profile). Native-profile AOT
  was then actually generated and compiled with
  `./gradlew :control-plane:processAot :control-plane:compileAotJava :control-plane:nativeCompile -x :control-plane:nativeCompile --no-parallel --console=plain --offline`,
  which succeeded in 4 s. The final native linker was not run.
- Full suite RED/fix/GREEN: the first all-task run exposed the close race retaining
  `Snapshot[inFlight=1, bufferedBytes=64]` after `close()`. `close()` now interrupts and closes its
  existing P12 owners, then waits for both owned executors to terminate before returning. The focused
  race test succeeded in 7 s. The final command
  `./gradlew test --rerun-tasks --no-parallel --continue --console=plain --offline` then succeeded in
  3 min 39 s (190 actionable tasks, all executed).

The controlled tests contain no fixed sleeps for ordering. Latches establish backend phases; raw
sockets exercise chunked framing, slow upload, caller non-consumption, and disconnect; bounded
futures/state waits provide failure deadlines. They cover fixed/chunked request overflow, aggregate
contention, a sub-chunk aggregate budget, inbound and full-backend deadlines, backend cancellation
on oversized response, response-write cutoff, disconnect/deprovision, health under count+byte
saturation, success, pre-dispatch URI failure, and zero count/byte ownership after every terminal
path and immediately after close.

GitNexus was bound to `nanofaas` at this exact worktree. Before editing,
`RoundRobinFunctionProxy` was HIGH (94 impacted, 15 direct, two process families);
`close` was HIGH (111/12, four process families); the one-argument constructor was HIGH (76/1);
and `RoundRobinFunctionProxyFactory.create` was HIGH (86/2). The audit preserved the interface,
existing constructors, provider ownership, provision/reconcile paths, and close retry semantics,
and exercised all provider dependants. `handleInvoke`, `forward`, `writeBackendResponse`,
`copyResponseHeaders`, factory class/constructor were LOW; the three-argument constructor was
MEDIUM. The Spring `managedFunctionProxyFactory` bean was UNKNOWN; exact text search resolved the
reflective Spring consumer and `ManagedFunctionProxyFactory` injection into
`ContainerLocalDeploymentProvider`, then the configuration real-path test covered it.

GitNexus 1.6.11's index was one commit behind at `c921af94`. Both
`analyze --index-only` and `analyze --force --index-only` failed with
`truncated:true`: 1,467/1,667 entry-point candidates dropped, 1,760 callees dropped and 48 walks
cut, leaving `incremental-in-progress`. The installed direct CLI backend was used for every exact
impact and pre-commit detection. Its uncapped all-scope structured result reported seven tracked
files, 28/28 returned changed symbols, three/three affected processes, medium risk,
`partial=false`, `truncated=false`, and `error=null`. The two unrelated tracked overload experiment
files are included in that all-scope count; the newly tracked P13 files are covered by the staged
rerun recorded in the P13 report.

P13 files: `application.yml`, `ContainerProxyProperties.java`,
`ContainerDeploymentProviderConfiguration.java`, `RoundRobinFunctionProxyFactory.java`,
`RoundRobinFunctionProxy.java`, `P13BoundedProxyTest.java`,
`P13ProxyConfigurationTest.java`, this status file, and the task report. Preserved without staging:
the two modified overload experiment files, six untracked GitNexus skill directories, and
`ReplicaStatusSnapshotConfigurationTest.java`.

Self-review and the full-suite gate found and fixed URI-failure ownership, sub-chunk allocation,
deadline-scope separation, and synchronous close ownership via additional RED cycles.
The remaining concerns are that JDK `HttpServer` interrupts a slow-upload socket, so the attempted
`408` is best effort and the client may observe an immediate close; actual `nativeCompile` was
not run; and GitNexus could not publish a refreshed index because its own full-process analysis
truncated. Next: P14; retain these P13 measurements for the P23 comparison.

## P13 fix round 1 — physical publisher ownership and deterministic write deadline

Starting revision `9955abbc`. The proxy now reserves the JDK byte-array publisher's additional
body-length copy while the original bounded request array remains owned, then releases that
reservation immediately after `HttpClient.send` returns. A blocked-backend regression observes both
simultaneous owners (16 + 16 = 32 bytes) and returns to zero afterward. The elapsed-time/kernel-
backpressure response test was replaced by package-private injected deadline/output seams: futures
prove backend-deadline close precedes response-deadline start, and manual expiry interrupts a
latch-blocked writer without sleep or elapsed-time ordering.

The original compilation-only TDD RED remains historical fact and was not rewritten. Uncommitted,
isolated mutation/revert runs supplied behavioral evidence: fixed overflow failed 413/200; chunked
input failed 413/503; slow upload failed with `SocketTimeoutException`; oversized response failed
502/503; saturation failed 503/500; and publisher accounting failed 32/16. Exact commands and full
context are in `task-P13-report.md`. After every production mutation was restored, focused P13 tests
succeeded in 8 s (39 tasks executed), the complete provider module succeeded in 11 s (39 executed),
bootJar succeeded in 1 s, and native-profile AOT generation/Java compilation succeeded in 3 s.
The initial P13 revision's fresh uncached full-suite result remains the broad gate because this round
only changes package-private proxy seams and request-copy accounting. P07/P12 lifecycle and I3/I7
generation/removal paths are unchanged. Final native linking remains P23 scope.

GitNexus fix-round detection used the uncapped direct backend. All-scope returned 8/8 changed
symbols and 3/3 affected processes across five files (the three P13 files plus two unrelated
overload files); staged scope returned 7/7 and 3/3 across exactly three P13 files. Both were medium
risk with `partial=false`, `truncated=false`, and no error. `git diff --cached --check` was clean.
