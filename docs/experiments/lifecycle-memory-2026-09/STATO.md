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

## P13 fix round 2 — request-graph lifetime and strict deadline ordering

OpenJDK 25.0.4 inspection establishes two simultaneous request-byte owners, not three:
`ByteArrayPublisher.content` aliases the proxy array, while subscription creates body-length
`ByteBuffer.allocate` storage; `HttpResponseImpl.initialRequest` retains the request/publisher
graph. The proxy now holds both byte leases through response buffering and caller writing, until a
helper return makes that graph releasable. A post-header latch-blocked writer observes 16 + 16 + 1
= 33 leased bytes and final zero.

The controlled deadline factory now throws immediately if the third (response-write) deadline is
created before the backend deadline is closed. An uncommitted ordering mutation that omitted the
backend deadline close failed with `response deadline created before backend deadline closed`; the
restored implementation and both new focused regressions are GREEN. The focused P13 suite and full
provider test task are GREEN, as are bootJar and native-profile AOT generation/Java compilation.
The earlier full-suite evidence remains the broad gate because this round is confined to one
provider proxy and its package-private test seams; no shared lifecycle or provider contract changed.
The original TDD chronology and round-1 mutation evidence remain unchanged.

## P14 — WaitEstimator bounded retention and measured bucket rejection

Starting revision `3ee63002e325b7fbf2a376000e711e9cd3c20232` on
`control-plane-lifecycle-memory`.

`WaitEstimator` retains exact timestamps while bounding state to 262,144 global
samples, 262,144 total per-function samples, 32,768 samples per function, 8,192
function states, and 16 distinct cleanup candidates per call. Dispatch and admission
calls clean inactive states through a rotation queue rather than a map scan. Direct
target checks retire their own empty state conditionally under the state lock;
explicit removal remains immediate. A monotonic high-water mark prevents backward
clock reordering, and saturated cutoff arithmetic covers the `Instant` range.

The predeclared bucket candidate used a 30 s window, 1 s resolution, 31 slots,
one-boundary-bucket absolute tolerance, at most 10% relative wait error after ten
samples, and identical admission/fairness/`Retry-After` decisions. Deterministic
one/1,000-function streams at 1/s and 1,000/s used three warm-ups and seven measured
repetitions. Regular-stream error was 9 events against a 10-event boundary bucket
and 2.90% relative wait error; fairness order was unchanged. The decisive boundary
burst had exact=0 and bucket=100 at 30.999 s, causing exact rejection but bucket
admission. Buckets were rejected despite lower cost/allocation.

Measured exact dispatch medians were 302/202 ns and 120 B per dispatch for one
function at low/high rate, and 920/990 ns with 475/456 B for 1,000 functions.
Bucket medians were 11–16 ns and 0 steady-state B. Exact retained samples were
31+31 at low rate and approximately 30,001+30,001/30,059 at high rate; after
idle plus the bounded cleanup calls, only the 1 or 63 newly active global/function
samples remained. Source and raw output are in
`P14WaitEstimatorMeasurement.java` and `P14WaitEstimatorMeasurement.out`.

Strict RED/GREEN evidence: the first retention test failed compilation with 16
missing bounded-state symbols; estimate-side idle cleanup then failed at line 45;
direct target retirement failed at line 57. Each corresponding focused GREEN passed.
The final sync-queue module ran 39/39 tasks, the control-plane `Retry-After`
integration ran 78/78 tasks, JVM packaging and native-profile AOT generation/Java
compilation passed, and the final full repository suite ran 190/190 actionable tasks
in 4 min 16 s.

GitNexus pre-edit risk was CRITICAL for `estimateWaitSeconds`, HIGH for private
`snapshot`, MEDIUM for the class, and LOW for resolved constructors/record/remove/
prune paths. The index was four commits stale; UNKNOWN constructor/field results
were resolved by exact text search and no absence was treated as safety. Final
all/staged detection and staged diff validation are recorded in the P14 report.
Remaining bounded-policy concern: beyond 8,192 represented functions, additional
functions use the existing global fallback rather than individual history.

Next: P15.

## P15 — Catalog snapshot cost and persistence correctness

Work started at `9f5c58db`; the final review/commit base is the preserved concurrent
MIT-license commit `779e14807ed9033117b9afc9aeee616759aa637f`. Snapshot persistence remains
the catalog format. Equal registry records, PATCH requests resolving to the current
record, and desired-replica requests already at target now avoid copying, saving and
provider/listener work.

Desired replicas are durable intent. A scale persists that intent before invoking the
provider; persistence failure is returned without calling the provider or changing the
published/reloaded catalog. Provider failure is also returned, but the new durable target
survives reload for reconciliation. Volatile replica observations are invalidated after
every provider attempt, including failures. Remove provider failure restores serving from
memory while leaving the unchanged durable snapshot untouched; if teardown completed but
catalog deletion failed, the reconciled provider metadata is still re-saved best-effort.
Controlled stores/providers and a barrier cover save/provider failures, no-op paths,
write-boundary reloads and concurrent updates.

Warmed single-operation measurements on Linux aarch64 6.17.0-1032-nvidia, OpenJDK
25.0.4, 20 CPUs and 121 GiB RAM follow. Duration includes a real temporary-file atomic
snapshot write and sync; writes are per measured mutation.

| operation | catalog | allocated bytes | serialized bytes | duration ns | writes |
| --- | ---: | ---: | ---: | ---: | ---: |
| register | 1 | 17,216 | 1,057 | 1,572,906 | 1 |
| update | 1 | 11,776 | 541 | 1,278,027 | 1 |
| remove | 1 | 53,544 | 34 | 1,771,480 | 1 |
| desired replicas | 1 | 21,192 | 582 | 2,481,029 | 1 |
| register | 100 | 1,085,344 | 51,439 | 4,803,484 | 1 |
| update | 100 | 1,081,888 | 50,923 | 4,014,447 | 1 |
| remove | 100 | 1,079,552 | 50,415 | 5,482,281 | 1 |
| desired replicas | 100 | 1,079,536 | 55,203 | 8,154,686 | 1 |
| register | 1,000 | 2,086,320 | 510,439 | 8,874,042 | 1 |
| update | 1,000 | 1,876,608 | 509,923 | 10,723,090 | 1 |
| remove | 1,000 | 1,747,240 | 509,415 | 5,741,592 | 1 |
| desired replicas | 1,000 | 1,871,192 | 554,703 | 4,416,749 | 1 |

The 1,000-entry maximum is 10.723 ms, about 0.214% of the default 5 s autoscaler
period; maximum measured allocation is 2,086,320 bytes and maximum serialized size is
554,703 bytes. This is adequate for the declared control loop, so P15 explicitly closes
the snapshot-replacement hypothesis. No ADR, journal, coalescing or copy-on-write catalog
was introduced.

Deterministic RED evidence captured failures for equal-record writes, same-target scale,
no-op PATCH notifications, redundant remove rollback writes, provider-failure desired-state
rollback and stale observed-state retention. GREEN includes the focused P15/registry slice,
autoscaler integration, bootJar and native-profile AOT generation/Java compilation. The
final repository suite passed all 190 actionable tasks from scratch in 4 min 8 s; the
post-review scale-persistence regression passed in the focused 78-task control-plane run.

Pre-edit GitNexus impact was HIGH for `FunctionRegistry.put` (66 impacted symbols, four
direct callers/four processes), MEDIUM for `FunctionService.update`, CRITICAL for
`ManagedDeploymentCoordinator.setReplicasLocked` (27 impacted symbols, two direct callers,
five processes), and LOW for `FunctionService.rollbackRemoval`. The UNKNOWN test symbol was
resolved by exact text search as JUnit discovery-only. The refreshed index still reported
process-layer budget truncation while building flows, so impact counts are lower bounds;
exact call-site searches and focused/full tests compensate. Final all/staged detect-change
results and the precise staged-scope limitation are recorded in the P15 report.

Changed production/test history is limited to the three registry/service classes, the
coordinator regression update and two P15 test classes; this status entry records the
decision. Next: P16.

## P15 fix round 1 — provider application and unavailable recovery

All three Important review findings are addressed above P15 commit `2c88d569`. A new
registry-owned volatile application-state table distinguishes durable desired state from
provider/listener application completion. It is structurally bounded to retained function
names, with at most one PATCH, scale and unavailable marker per name; provider resource
diagnostics are capped at 64 bounded strings. Identical PATCH/scale retries now reapply the
provider/listeners after failure without another catalog save. PATCH markers clear after
provider/listener success; scale markers clear after provider success and are the only
application markers cleared by coordinator shutdown. Durable removal clears all marker
kinds, while a new registry/startup lifecycle gets fresh volatile state. They are never
serialized.

If deprovision succeeds but catalog deletion and rollback reconcile both fail, the durable
record remains an internal recovery handle. The function is excluded from get/list and
invocation-visible service paths and listeners are not replayed. A delete retry can finish
cleanup in-process; after restart the existing restorer/reconcile path alone decides whether
the function becomes available and replays listeners. Manual scale now performs availability
checking and lookup under the same per-function lock as removal.

Deterministic RED evidence covered failed-listener and failed-provider identical PATCH,
failed-provider identical scale, the deprovision/save/reconcile triple failure, restart
recovery, and a latch-driven partial-deprovision/scale race. The HTTP conflict mapping also
had an explicit compile RED before its handler was restored. GREEN proves two catalog writes
total for register plus PATCH, and two for initial record plus desired-replica mutation;
identical retries add zero writes. The race returns pending-removal conflict and never calls
provider scale. At 1,000 durable functions, 2,000 simultaneous PATCH/scale markers allocated
200,480 bytes in the diagnostic run, remained exactly one entry/two markers per function,
and returned to zero after completion and removal. Reload/startup also clears old volatile
markers.

Verification was GREEN for the focused regressions, the full control-plane suite (78
actionable tasks), autoscaler/concurrency integration plus native-profile AOT Java
compilation (46 actionable tasks), and the final complete repository suite (190/190 actionable
tasks in 4 min 46 s). The original 1/100/1,000 catalog measurement matrix and snapshot
decision remain unchanged: snapshots are adequate and the replacement hypothesis stays
closed. The benchmark-stability Minor remains deferred as requested. Next: P16.

## P14 fix round 1 — review findings closed

Starting revision e0aa2673. The existing owned SyncScheduler now invokes bounded
estimator maintenance on every cycle, including its 500 ms empty-queue safety
wake. No executor was added; SmartLifecycle.stop() interrupts and shuts down the
worker. The real scheduler/queue test proves idle cadence and no post-stop
maintenance with condition-based deadlines.

Scheduled maintenance gives global and function history independent shares:
4,096 global timestamps and 16 unique function candidates with 256 timestamps
per candidate per cycle. Atomic queue markers prevent duplicate amplification.
At the 8,192-state or total-sample ceiling, a bounded full-map check evicts an
actually expired state first. Conservative infinite wait occurs only while all
slots are live; production admission turns it into EST_WAIT with finite configured
Retry-After 7. Later expiry recovers the slot and finite fallback.

The bucket prototype now cleans idle slots/states, supports explicit removal and
caps function state. Fresh exact/bucket instances receive identical forward-only
streams. Admission, fairness, zero-approximation error, lifecycle, 8,192/8,193,
recovery and sample-cap behavior are assertions; post-idle cost and allocation
are measured. Buckets remain rejected because the boundary burst changes
admission. Full data and commands are appended to the P14 report.

Focused sync-queue tests passed 39/39, HTTP integration 78/78, JVM/native-profile
AOT checks passed, and the final repository suite passed 190/190 in 3 min 44 s.
GitNexus pre-edit risk: CRITICAL estimate, HIGH snapshot, MEDIUM estimator/service,
LOW scheduler/resolved cleanup paths; stale-index limits are in the report.
Final GitNexus all scope returned 12 files/14 symbols and staged scope exactly ten
P14 files/13 symbols; both reported the eight audited scheduler flows at HIGH
risk, with no partial/truncated marker. Staged diff validation passed.

Next: P15.

## P15 fix round 2 — atomic registry publication

Both remaining Important findings and the deterministic race-test Minor are closed (3/3).
Removal start now preserves pending PATCH/scale markers across every failed or aborted delete;
only durably published catalog deletion removes all application state. Deterministic
failed-PATCH/failed-DELETE/retry and failed-scale/failed-DELETE/retry tests prove the
identical retry performs its provider side effect, adds no snapshot write, and then becomes
a true no-op after success.

`FunctionRegistry` now publishes one immutable volatile pair of recovery/public maps.
Public/service/invocation get/list paths can only read the public map. Managed deployments
loaded from disk begin recovery-only before readers and services exist; LOCAL and EXTERNAL
records remain public. The restorer consumes an explicit package-private recovery view and
atomically publishes only successful reconciles. Failed rollback atomically retains a raw
durable recovery record without listener replay or public visibility. Latch tests cover a
reader already inside lookup during rollback and precreated readers while startup reconcile
is blocked.

Marker ownership is explicit: `FunctionApplicationState` owns all volatile marker kinds;
`FunctionService` completes PATCH, `ManagedDeploymentCoordinator` completes scale and its
`close()` clears only scale markers, durable registry deletion prunes every marker, and a
new registry/startup lifecycle gets a fresh table. Markers remain bounded by durable names
and are never persisted. The 1,000-name/2,000-marker diagnostic remains 200,480 allocated
bytes and returns to zero.

The final diagnostic snapshot matrix remains one write per operation. The round-2 rerun
maxima are 8,415,955 ns, 2,256,016 allocated bytes and 554,703 serialized bytes at sizes
1/100/1,000. Snapshots remain adequate and the replacement hypothesis stays closed; no
journal was introduced. Focused pending tests (12), the 103-test registry slice, HTTP,
autoscaler/concurrency integration, bootJar, native-profile AOT Java compilation and the
complete repository suite (190/190 actionable tasks in 3 min 19 s) are GREEN. Only the
single-sample benchmark-stability Minor remains deferred. Next: P16.

## P15 fix round 3 — unavailable retry rollback

The sole Important re-review finding is closed (1/1). If retrying removal of an already
unavailable record reaches an ordinary deprovision failure, `FunctionRegistry` now decides
under its own lock that rollback remains recovery-only. Public registry get/list and the
direct `DeploymentWakeUpGate` consumer cannot observe or use the record; no service-level
check-then-act was added.

The deterministic RED failed both the public registry assertion and the real wake-up
consumer assertion. GREEN retains the raw durable recovery record plus pending PATCH,
scale and unavailable markers, performs no extra snapshot write, and later completes delete
with the fourth total write before returning marker/retained-name counts to zero. Durable
delete prunes every marker kind; startup resets the full volatile table, while coordinator
shutdown intentionally clears scale markers only.

Focused recovery/wake-up/removal/restorer/HTTP tests, autoscaler/concurrency integration,
bootJar, native-profile AOT Java compilation and the complete repository suite (190/190
actionable tasks in 4 min 9 s) are GREEN. The single-sample benchmark-stability Minor remains
the only deferred P15 item. Next: P16.

## P16a — SDK memory inventory and common saturation wire corpus

**Revision / scope.** Started from `9ad0f87e2db260193ce09e72399c7b3086ee4cd7`
with external MIT commit `779e1480` preserved in history. P16a inventories Java,
Java-lite, Python, Go and JavaScript runtime retention, freezes one common wire policy,
and adds executable adapters for one shared JSON corpus. It deliberately does not claim
runtime quota conformance or implement the P16b byte/count limits. Protected overload
experiment files, untracked GitNexus skills, the untracked replica-status test and ignored
progress ledger remain untouched and excluded.

**Implemented.** `sdks/runtime-contract/saturation-wire-corpus.json` is the sole source of
expected saturation, payload, handler-timeout, callback-delivery-exhaustion and stopping
outcomes. Its embedded runner contract requires unique cases, finite positive deadlines
under a finite ceiling, valid HTTP/no-second-response status, complete observable errors,
release sets and success/error/cancel/stop policy. Thin adapters in all five languages parse
that file and validate those invariants; expected IDs/statuses/codes/messages are not copied
into the adapters. The companion README defines fail-fast admission before handler/avoidable
large copies, `429` saturation, `413` payload rejection, attempt-level `504`, accepted-during-
stop `503`, finite waits, physical release, callback failure observation and preserved
execution/dispatch identities with control-plane-owned retry.

**Inventory / ownership.** Java callback retention is bounded by two default workers plus a
fixed 128 queue, while its virtual-thread handler executor is unbounded. Java-lite has the
same default callback shape but unowned server/HTTP/executor shutdown gaps. Python's 128
callback semaphore is real, while default `to_thread` queues and timed-out sync work are not
bounded by worker count. Go has 128 buffered plus two active callbacks by default, but
unbounded request/handler goroutines. JavaScript has a real 128 active-callback Promise cap,
not a worker queue, while request/handler promises are unbounded. No runtime currently has
both single-payload and pending-callback-byte caps. Exact retained input/output/callback,
owner, release, timeout and stop paths are tabulated in the README.

**Routing / compatibility.** Every nonconforming corpus row is assigned explicitly: P16b
owns common direct-invocation admission, byte/count, wire and observation conformance; P17
owns Python physical handler/callback executor and shutdown truth; P18 owns Java-lite
executor/client/start-stop lifecycle and verifies the Spring Java HTTP ownership pattern.
There is no unresolved P01 incompatibility: SDK handler timeout is an attempt-level `504`,
not the per-waiter `408`; retry preserves `X-Execution-Id` and `X-Dispatch-Attempt`, and the
SDK never invents a second execution identity.

**RED/GREEN evidence.** Before the corpus existed, all five new adapters failed on the
missing shared file (Java and Java-lite `IllegalStateException`, Python `FileNotFoundError`,
JavaScript test failure, Go explicit missing-corpus path). After adding the corpus, all five
focused adapters passed. Synchronization relies on file completion and finite corpus
deadlines; no sleep orders these tests.

**Verification.** Java and Java-lite focused adapters passed in a fresh 16-task Gradle run;
both complete SDK `build` tasks passed with 18/18 actions. Python focused adapter passed,
the complete suite passed 61 tests (35 existing deprecation warnings), and `uv build`
produced both wheel and sdist. Go focused and complete suites passed on exact Go 1.24.0;
`go vet ./...` reached a pre-existing unrelated `cold_start.go:27` atomic no-copy warning,
which was not changed in P16a. JavaScript focused adapter and complete 39-test suite passed;
`npm run build` passed. The first sandboxed JavaScript suite attempt could not bind localhost
(`EPERM`); the required socket-enabled rerun passed all tests. Full repository
`./gradlew build --rerun-tasks --no-parallel --continue --console=plain --offline` passed in
4 min 14 s with 240/240 actionable tasks executed.

**Impact.** GitNexus 1.6.11 was refreshed at the exact base to 19,590 nodes, 56,459 edges,
870 clusters and 767 flows. Its SDK callback/payload query identified the Java callback
submit/serialization process and Python/JavaScript invoke/callback owners used by the
inventory. P16a edits no existing code symbol—all corpus, adapter and report code files are
new, and STATO is append-only—so no existing-symbol impact gate or HIGH/CRITICAL pre-edit
risk was crossed. Final all/staged graph detection and exact commit scope are recorded in
the forced-added P16a report.

**Next step.** P17 and P18 establish their physical ownership primitives, then P16b drives
the shared corpus through real direct invocations and implements/observes all remaining
count and byte quotas.

## P16a fix round 1 — structured executable policy

Starting from `1cd7a295caca889e00960d7813974267d281ecc5`, the one Critical, four
Important findings and report-accuracy issue are addressed without runtime production
changes. The v1 free-form stimulus corpus is superseded by versioned v2 scenarios with
finite runtime limits, requests and identity, byte relations, deterministic backend
behavior, sequenced actions/barriers, full retained-resource counters, exact wire
responses/headers, explicit handler/callback lifecycle and finite deadlines.

The eleven scenario kinds cover success drain, separate ingress/output oversize,
callback saturation, handler timeout, cancellation, health under saturation, stop with a
full queue, restart, delivery exhaustion and two-attempt dispatch identity. P01 identity
is explicit: execution ID stable, invocation attempt incremented by control-plane
redispatch, callback retries echoing that attempt, and zero runtime redispatch.

One shared Python validator owns structural and semantic rules. Ten embedded mutation
fixtures and one raw non-finite-number mutation prove missing booleans, headers and
observations; invalid limits/deadlines; contradictory lifecycle; impossible callback
delivery; altered identity; and wrong output lifecycle are rejected. All five native
adapters invoke it with a finite process deadline and parse typed projections from the
single corpus. This executes policy/schema semantics only—not scenario actions against
real runtimes or real timing/counter conformance, which remain P16b/P17/P18.

Inventory now also records the Go dispatcher leak on bind failure, JavaScript
non-positive/non-finite callback queue configuration, and absent runtime-owned ingress
body-read deadlines in all five SDKs. Go/JavaScript fixes and common ingress conformance
route to P16b; Python physical ownership remains P17 and Java-lite lifecycle ownership
remains P18. Verification and final graph gates are recorded in the force-added P16a
report. Next: P17/P18, then P16b.

## P16a fix round 2 — sole policy definitions and semantic connections

Starting from `68e347bb17af9cf61a2c45e52b718eb8abc6ea41`, the open one Critical and
two Important findings are addressed without runtime production changes. Honest RED added
seven independent mutation probes and observed all seven accepted by v2. Version v3 now
places every policy value in one referenced `contractDefinitions` set: vocabularies,
actor/action permissions, size relations, backend behavior/lifecycle compatibility, exact
wire outcomes, callback transport and envelopes, identity/cross-field rules, observation
sets and final-counter policy.

The validator now supplies schema mechanics plus generic reference/projection/relation
operators only. It resolves each scenario's exact response, handler/callback lifecycle and
callback request projection from those definitions. Callback URL presence controls
requirement; behavior controls legal lifecycle; attempt/delivery booleans and counts agree;
callback retries preserve the invocation dispatch attempt; and runtime redispatch remains
zero. Exact callback method, URL, content type, trace/attempt headers and success/error
payload are available to P17/P18/P16b without runtime-specific invention.

GREEN rejects all seven review contradictions and six additional broken-reference/relation
probes; all 23 embedded mutations and the raw non-finite JSON probe pass. The corpus is kept
compact at 660 lines by defining callback transport once and storing one exact request
projection plus per-attempt identity sequence. All five adapters invoke the same validator
under a finite 10-second deadline and parse the one JSON source with native typed,
presence-safe projections. This is schema/semantic execution, not timed runtime
conformance; P16b/P17/P18 ownership assignments in the README remain unchanged.

Round-two verification passed all five focused adapters, Java/Java-lite complete builds
(18/18 actions), Python 61 tests plus wheel/sdist, Go's complete suite, JavaScript 39/39
plus package build, and the full Gradle repository build (240/240 actions, 4 min 17 s).
Go vet retains only the pre-existing `cold_start.go:27` atomic no-copy warning. An initial
full-build run saw one EOF in an existing container-provider timeout test; the exact test,
its complete 92-test module and the full rerun all passed, so no out-of-scope edit was made.

## P16a fix round 3 — coordinated semantic consistency

Starting from `5e6a0eeb372b308f4111a9c466c7d1df14c6cfe5`, the independent reviewer confirmed
the seven individual contradictions were rejected and the corpus remained the sole policy
authority, but found coordinated canonical mutations could still disconnect handler, wire and
callback semantics; callback delivery attempts also did not constrain dispatch-attempt sequence
length. Four event-free deterministic RED tests reproduced all cases.

The GREEN model adds declarative lifecycle-to-wire, wire-to-envelope, action request-reference
and callback-attempt cardinality rules to the corpus. The validator implements only generic
mapping, presence and length operators. Exact upstream GitNexus impacts for `validate_rule`,
`validate_expected` and `apply_rules` were LOW and limited to the Runtime-contract validator
flow. Focused and full verification plus final graph gates are recorded in the P16a report.

## P16a fix round 4 — stimulus and scenario anchors

Starting from `088c3497`, a fresh reviewer showed five further coordinated rewrites were accepted:
output size relation could disagree with lifecycle, a scenario kind could be rewritten into another
canonical outcome chain, and health/invoke request roles could disagree with request actions.
Five deterministic tests reproduced the gap before implementation.

The corpus now declares scenario-kind/outcome, size-relation/lifecycle and action/request-role
compatibility. Python only exposes generic scenario and referenced-request contexts to generic
mapping rules. The validator suite is 12/12 GREEN; adapter and graph verification are recorded in
the P16a report after completion.

## P16a fix round 5 — causal harness programs

Starting from `bb081205`, fresh review isolated the final semantic gap to unanchored harness and
retry ordering. Five deterministic RED tests covered reversed retry outcomes, missing redispatch,
missing stop/capacity-fill phases and swapped success/restart kinds.

The JSON policy now owns exact action, actor and ordered outcome sequences for every scenario kind;
the Python validator adds only a generic mapped-sequence comparison. The refreshed-index impacts
for `validate_rule` and `apply_rules` were exact LOW. Validator GREEN is 17/17; adapter and graph
verification are recorded in the P16a report after completion.

## P16a fix round 6 — action request targets

Starting from `79decd42`, fresh review found causal action/actor/outcome sequences did not anchor
their request targets. Four deterministic RED tests covered swapped retry targets, pre-send waits,
wrong redispatch target and premature callback wait.

The JSON policy now adds exact per-kind request-target and barrier sequences, completing the
action projection. The generic sequence operator accepts finite JSON values so `null` slots remain
first-class. Refreshed-index impact for `validate_rule` was exact LOW; validator GREEN is 21/21.

## P16a fix round 7 — barrier producers and consumers

Starting from `a9b90fad`, fresh review found the action-side barrier sequence did not constrain the
backend producer. A deterministic RED redirected the timeout handler to a declared `decoy` while
the harness still waited on `handler-started`, reproducing an accepted deadlock.

The JSON policy now anchors barrier declarations plus handler/callback producer sequences per kind.
The generic sequence validator permits empty finite sequences for barrier-free scenarios. Exact
impact for `validate_rule` was LOW; validator GREEN is 22/22.

## P16a fix round 8 — barrier initial state

Starting from `7504bf9a`, fresh review identified the last free barrier field: `initialState`.
A parameterized RED accepted `open` in timeout/cancellation/stop and an arbitrary `banana` value.
The JSON policy now fixes `closed` for all barrier scenarios and empty state sequences elsewhere;
the generic validator code is unchanged. Validator GREEN is 23/23.

## P16a fix round 9 — ordered request identity

Starting from `0b00c17c`, fresh review showed coordinated collection reordering could detach
dispatch metadata and ordered outcomes from action request targets. Two deterministic RED tests
covered swapped attempts hidden by request order and success-then-failure hidden by response order.

The JSON policy now declares canonical request-ID order per kind and aligns response, handler,
callback and backend collection order to `requests[]`. Python remains unchanged. Validator GREEN
is 24/24.

## P16a fix round 10 — dispatch-attempt origin

Starting from `fc32aeca`, fresh review found a coordinated `[1,2]` to `[2,3]` shift passed equality
and increment checks. A deterministic RED updated metadata, identity and callback projections
together. JSON policy now fixes the dispatch-attempt sequence per kind, including `[1,2]` for retry
and `[2]` for the standalone second-attempt callback scenario. Python is unchanged; validator
GREEN is 25/25.

## P16a complete

Final revision `86fb6b17` passed fresh independent review with 0 Critical, 0 Important and
0 Minor findings. The final validator suite is 25/25, all embedded mutations and five focused SDK
adapters are GREEN, and fresh full-repository verification reran 240/240 Gradle tasks with
`BUILD SUCCESSFUL` in 4 min 2 s. P16a is closed; next is P17, then P18, then P16b.

## P17 — Python physical handler and callback ownership

Starting from `b3cc08ad`, P17 replaces `asyncio.to_thread` ownership ambiguity with
runtime-owned, separately bounded handler and callback executors. Handler admission is
bounded before submit (default 32); callback submit admission is bounded (default 128)
and uses two independent callback workers. Physical handler handles remain counted after
HTTP timeout until the real thread/task completes. Separate metrics expose active handlers,
timed-out waits, pending callbacks, active callback workers and handler saturation.

Shutdown stops admission, requests async cancellation, closes owned executors to new work
and awaits event-driven drain for a finite configurable bound (default 5 s). Cooperative
async cancellation drains without blocking the ASGI loop; non-cooperative Python threads
remain counted and are reported rather than falsely claimed killed. Acceptance, submit and
manager registration are atomic against stop, closing the independently reviewed race that
could report `drained=true` while a handler thread was already active.

Deterministic RED/GREEN tests cover sync timeout retention and admission, delayed async
cancellation, cooperative and non-cooperative shutdown, callback isolation/progress,
finite-positive configuration and the submit/register race. `uv run pytest -q` passed
76 tests; the P16a Python adapter passed 1/1 and `uv build` produced wheel and sdist.
The final independent re-review is CLEAN (0 Critical, 0 Important, 0 Minor).

GitNexus exact Python-symbol impacts returned no graph result and were treated as UNKNOWN;
exact text search resolved local runtime/test callers. The pre-stage complete all-scope
gate reports four files/22 symbols, zero affected processes and LOW risk. Final all/staged
gates and commit revision are recorded in the P17 report closure.

P16b retains two explicit contract tasks: reserve callback count/bytes before handler
start, and canonically adopt or replace the currently Python-specific retryable
`429 RUNTIME_HANDLER_SATURATED` outcome. Next: P18, then P16b.

P17 implementation is committed as `a6824ba6`. Its final pre-commit GitNexus all-scope
gate completed with six files/24 symbols and LOW risk (including protected user dirt);
the staged gate completed with exactly four P17 files/23 symbols and LOW risk. Both had
zero affected processes and neither was partial or truncated. The staged diff was clean.

## P18 — Java-lite executor/client ownership and bounded shutdown

Starting from `398387ba`, P18 gives every Java-lite asynchronous resource an explicit
lifecycle owner. `NanofaasRuntime` retains its HTTP executor and callback client, fences
admission before closing the listener, performs idempotent bounded shutdown, releases the
blocking `start()` caller, removes its shutdown hook and preserves interrupts. The builder
cleans up a client/executor/server created before bind or another partial-build failure.
`InvokeHandler` retains physical handler task/thread handles through real exit, owns only
the callback executor it creates, drains callbacks before cancellation and returns the P16a
`503 RUNTIME_STOPPING` outcome for work arriving after stop begins. `CallbackClient`
boundedly closes only the JDK client it created.

The Spring Java SDK had one confirmed ownership gap: `HttpClientConfig.restClient()`
created a local JDK client that no bean owned. The client is now a conditional Spring bean
with explicit `close` destruction and is injected into `RestClient`; an external client
bean retains its declared ownership. Existing `HandlerExecutor` and `CallbackDispatcher`
`@PreDestroy` owners were verified and left unchanged.

Twelve deterministic RED/GREEN groups cover blocking start release, active physical handler,
owned/injected callback client, bind and partial-start failure, owned/injected HTTP executor,
owned/injected callback executor, active callback drain and named worker exit, finite stop,
stop admission, Spring owned/injected client, and interrupted start. Three warmed HTTP cycles
prove only controlled named threads exit and each exact listening socket refuses connections
after stop; no unrelated JVM thread/socket count is asserted.

Focused P18 verification passed 18/18 tests. The complete Java-lite and Spring Java suites
passed respectively 37/37 and 94/94 tests with no failure/error/skip. Both P16a Java corpus
adapters passed, and
`./gradlew :sdks:java-lite:build :sdks:java:build --rerun-tasks --no-parallel --console=plain --offline`
executed 18/18 tasks with `BUILD SUCCESSFUL` in 13 seconds.

GitNexus was refreshed on this checkout. Exact impacts were LOW for
`NanofaasRuntime`, `start`, `stop`, `Builder.build`,
`InvokeHandler.shutdownCallbacks`, `invokeWithTimeout`, callback-executor creation and
`handle`. Java-lite `CallbackClient` was HIGH (58 symbols/four modules/one build process);
the change was limited to ownership and full callback/corpus suites remained green.
Spring `restClient` was UNKNOWN because bean wiring has no call edge; text search resolved
the callback consumer and the 94-test Spring suite verified it. Pre-stage all-scope detection
completed at HIGH aggregate risk for 6 indexed files/37 symbols and 8 flows, including the
protected overload-path dirt that remains unstaged. Final staged detection and commit SHA are
recorded in the P18 report.

No hard termination is claimed for a handler that ignores virtual-thread interruption: stop
returns at its configured deadline, logs the still-active physical owner, and that owner
releases only on real exit. P16b remains responsible for common callback/input/output quotas
and full direct-runtime corpus conformance.

The first independent P18 review found two Important startup-hook gaps: stop could pass hook
publication in a forced concurrent interleaving, and registration failure skipped cleanup of
resources already created by `build()`. Deterministic RED tests now inject a blocking or rejecting
hook owner. Hook publication and the lifecycle transition are serialized, and registration
failure enters the same bounded cleanup path while preserving the original exception. Focused
GREEN verification also proves matching hook removal plus exact listener, callback-client and
server-executor closure. Final suite verification and clean re-review remain pending.

The next re-review confirmed those two lifecycle bugs closed but found two additional Important
gaps. Spring callback client selection was type-wide and ambiguous with multiple host
`HttpClient` beans; it now uses a dedicated conditional bean name and qualifier, with deterministic
coverage for unrelated clients and an externally owned named override. The hook-race test now
waits until the stop thread is actually `BLOCKED` on the lifecycle monitor before releasing hook
registration, so it forces the rejected interleaving. The combined focused suite is GREEN 16/16;
full verification and another independent re-review remain pending.

The subsequent review verified all four previous findings closed and found one remaining
Important wire collision: a user handler's `RejectedExecutionException` was mistaken for the
runtime's stop-admission signal. A deterministic HTTP RED reproduced 503 instead of the pre-P18
500 handler-error path. Stop admission now uses a private lifecycle-only exception; handler and
ownership tests are GREEN. Full verification and a clean independent re-review remain pending.

P18 is closed at `fadc20f5`: the final fresh independent re-review is CLEAN (0 Critical,
0 Important, 0 Minor). It independently confirmed the private stop-admission signal, the four
earlier lifecycle/Spring fixes, Java-lite 40/40, Spring Java 96/96 and a focused 13/13 run.
P16b is now unblocked.

## P16b — bounded callback/payload ownership in all SDK runtimes

Starting from `7f8f4c2e`, P16b applies one finite cross-language policy to Java, Java-lite, Python,
Go and JavaScript: 32 active handlers, 1 MiB input/output, 2 MiB per callback, 128 pending callbacks,
16 MiB pending callback bytes, three attempts, a 30 s handler timeout and 5 s body/callback-attempt/
shutdown deadlines. Count and worst-case callback bytes are reserved before handler start; all
owners release on success, failure, cancellation, drain or bounded stop.

All five SDKs execute the 12-scenario shared corpus directly against runtime owners, bypassing the
control plane. Adapters verify actual HTTP/ASGI responses, callback projection/metadata, handler/
callback lifecycle, dispatch identity, observations and initial/final counters. Mutation tests
reject structurally valid expectation and byte-counter changes, closing review findings where an
adapter was green while only checking scenario names or echoing fixture values.

Coordinated verification is green: aggregate Gradle build 240 tasks; fresh Java/Java-lite/warm-echo
test tasks 21/21; Java 143 tests, Java-lite 81, Python 139, JavaScript 80; Go normal/race/vet; shared
validator 26 tests plus all embedded mutations. Runtime-backed RSS evidence is recorded separately
from control-plane memory in the P16b report. Every runtime slice has a final CLEAN review. The
last warm-echo review repeated its six real callback-delivery tests 20 times and found no uncaught
callback-thread exception. The all-scope GitNexus gate completed with 49 files/673 symbols/67 flows;
the selectively staged gate completed with 116 files/1,771 symbols/67 flows. Both retain CRITICAL
aggregate risk, are analytically complete, and reported neither `partial` nor `truncated`; the
staged CLI output capped only the displayed symbol list while retaining complete counts and risk.
P16b is closed at `1432cc5d`; therefore the requested P13–P18 block is complete.

## P19 — corrected immutable control for P23 (2026-09-11)

Status: **DONE_WITH_CONCERNS**. Production B remains exactly
`6d08303371d803f44187ec5f4e37827d54fec597`; diagnostic A is
`61d72e73528db62cf8ca465c6a037981d7ec13b0`, never an acceptance control.
No production behavior changed and P20b was not started. The sole implementer
used no subagents. The final authoring timebox stopped additional framework work.

The repository-owned artifact is
`p19/dossiers/49f98dabf572a260125d77989db7802d7a0020c11595a9ea78250cb729e27380/`.
Its manifest SHA-256 is the directory name; schema `nanofaas-p19-dossier-v1`.
`p19/baseline.json` is the P23 pointer. There are 714 hashed payload files,
52,472,766 payload bytes: raw request records, snapshots, complete Prometheus
samples, verified-GC checkpoints, histograms, role-separated process observations,
commands/exits/test methods, environment/configuration/workload identities,
per-entry build hashes, exact measured JVM jars and production/build source archives.
The exact common runner is in `executed-common-harness.tar.gz`; the final ASYNC
observer refinement and dossier tools are separately in `harness.tar.gz`.

Fresh G1–G18 passed **752 tests, 0 failures, 0 skips**, including every R1–R8
(nine regression methods). Counts by group: 7/68/96/44/33/73/13/1/21/40/85/25/12/68/54/28/38/46.
Supplemental Java/module/SDK/selector selections passed 486 tests; actual Docker
adapter lifecycle passed 2: **1,240 Java test invocations** overall, including
intentional profile repeats. Python 139, JavaScript 80, Go normal 116 and race 116
(including subtests), shared validator 26 all passed with zero failures/skips;
Go vet exited 0. Nine dossier/accounting contract tests passed. Four invalid module
selections exited the expected 1 with genuine selector diagnostics. Seven JVM
packages built and passed real health/OpenAPI/start-stop smoke. Exact commands and
method-level results are in `verification/`, `external/`, `smoke/` and
`verification-summary.json`; non-test commands have not_applicable counts.

Real configured HTTP SYNC/EXTERNAL ran fresh alternating A1/B1/A2/B2/A3/B3,
each 6,000 warm-up then 12,000 offered/admitted/unique terminal successes at
200 offers/s, zero refusal/unresolved work, identical four-core affinity, JDK25,
G1 and 256/512 MiB initial/max heap. Ordinary latency excludes warm-up and explicit
GC/checkpoints. Useful throughput was 199.945–199.994/s for B. B p50/p95/p99 (ms):
1.671/3.428/4.161, 1.634/3.304/3.920, 1.695/3.350/3.956.
Paired p99 deltas: +3.349%, −2.546%, −1.963%; allocation/success deltas:
+4.250%, +3.674%, +4.056%. B allocations were 143,947–144,014 bytes/success.
These are fixed-load diagnostic costs, not sustainable peak throughput or statistical
equivalence. The JDK process-total allocation collector passed escaping 64 MiB
platform-thread and completed-virtual-thread validation; backend/generator are separate.

Three current configured ASYNC processes each completed 120/120 unique successes
at 50 offers/s across flat/deep/wide/large shapes. p50/p95/p99 (ms):
12.487/22.933/26.891, 11.476/20.634/27.089, 15.209/22.937/25.130.
R1 correctly declines deep/wide archive outcomes; a bounded scalar-only terminal
observer counts actual completions and drains to zero. Declined deep replay returned
410 without another backend attempt; actual eviction race is covered by fresh R2.
The separate fresh configured T2 saturation case admitted 63/100 large offers and
refused 37 on input quota, explicitly separated from useful work. Its older P07
percentile/allocation semantics are not used for comparative acceptance.

All B quiescent checkpoints show zero live/execution/input/copy/waiter/observer-event
owners; function-capacity/name/registered-meter/retiring-offload owners reach zero
after removal. Post-policy used heap: B SYNC 41.14–41.44 MB; B ASYNC 25.49–25.65 MB.
Store size accessors perform existing cache maintenance before verified GC: this is
observer-assisted post-policy evidence, not an untouched-idle sweep guarantee.
Warm-up-to-drain framework/Prometheus heap growth is not classified as a new retainer;
the short repeat/profile, histograms and owner census do not substitute for P24 soak
or dominator analysis. The observer dictionary explicitly marks unobservable,
unselected and external populations unavailable/not_applicable, never fictional zero.
Fresh focused T3–T9/SDK ownership tests cover physical cancellation, churn, wake-up,
transport/proxy, bounded stop and catalog mutation; no second integration framework
or full cross-hop numerical-memory claim is made. P14's prior accepted measurement
decision is retained; its retention tests are fresh, not its standalone benchmark.

Docker was available after sandbox escalation. Minimal native compilation and
health/OpenAPI/start-stop smoke passed (image digest
`sha256:e5cd87f4e0d6c0a12c9a8a9563e0cb4d138682ef38b1b52c35774b48910d76af`).
Managed Kubernetes native compilation failed with Java heap OOM at 414.4 s under
the explicit 4 GiB compiler bound (native-image exit 3, outer build exit 1).
Per user direction, fresh JVM provider gates/startup are the bounded fallback;
the failed native gate is **not green**. No provisioned VM/cluster exists, so no
Kubernetes NanoLab lifecycle claim. Full NanoLab multi-container scenario not run;
real Docker adapter plus existing per-hop and five SDK suites are the proportional
fallback. Helm lint/render and Compose config passed; these are not deployment tests.
Separate observer-overhead passes were not run under the timebox; observer cost is
included and unquantified. Affinity is not exclusive host CPU reservation.

Systematic debugging rejected exploratory ASYNC polling/expiry assumptions without
changing runtime code. A real dossier verification RED exposed Path-vs-JSON checksum
ordering; sorted checksum emission fixed it, and full freeze/verify plus all nine
contracts passed. Rejected/pilot evidence is labeled, not counted as acceptance.
GitNexus graph context and upstream exact-symbol checks cover consumed/edited harness
symbols; UNKNOWN Python/dynamic edges were resolved by text search, not treated as
unused. One probe-main refinement's exact check was retrospective, a workflow caveat;
no runtime symbol was edited. Final complete all/staged gates and commit identities
are recorded in `task-P19-report.md`. Self-review checked denominators, identities,
missing-observer semantics, archive integrity and preserved production source.

Reverify from the repository root:

```bash
python3 -m unittest discover -s docs/experiments/lifecycle-memory-2026-09/p19 -p 'test_*.py'
python3 docs/experiments/lifecycle-memory-2026-09/p19/dossier.py verify docs/experiments/lifecycle-memory-2026-09/p19/dossiers/49f98dabf572a260125d77989db7802d7a0020c11595a9ea78250cb729e27380
```

Both exited 0. Baseline protected-file SHA-256 audit passed for both dirty overload
files, the untracked snapshot test and all six GitNexus skill directories. Those
files remain unstaged; their original patch is retained only outside the repository
dossier. Production diff against B is empty. Full implementation report:
`.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P19-report.md`.

## P19 fix round 1 — 2026-09-11 — BLOCKED

This section supersedes the earlier P19 acceptance claim. The latest user timebox
stopped further harness design; current evidence was executed and frozen. No P20b,
subagents, runtime changes or protected-file staging. Review findings 3/4 closed;
finding 1 only partially closed, finding 2 concretely red. No accepted P23 control.

Identity validation now binds exact A/B revisions to distinct measured artifacts,
actual config/workload documents and raw request schedule, boot/PID/start ticks,
chronological whole-process non-overlap and process exit evidence. Timeout handling
supervises an owned process group with bounded TERM/grace/KILL and saved failures;
cleanup starts before the first child spawn. Systematic-debugging/TDD reproduced
seven identity rejections, one re-signed artifact-binding rejection and two child
cleanup failures before fixes. Direct live-store future observation was RED before
adding the observer, then GREEN with two retained futures, one open, then zero.

Fresh verification (all exit 0 unless explicitly red):

- `python3 -m unittest discover -s docs/experiments/lifecycle-memory-2026-09/p19 -p 'test_*.py'`:
  20 tests, 0 failures/errors/skips; `java -cp /tmp/nanofaas-p19-r1.9kgN9P/probe P19ProbeContract`:
  PASS. Allocation validator: 67,108,864 escaped payload bytes per thread mode;
  observed platform 67,155,880 and virtual 67,430,808 after exit, both valid.
- `taskset -c 5-8 python3 docs/experiments/lifecycle-memory-2026-09/p19/campaign.py /tmp/nanofaas-p19-r1.9kgN9P`:
  A1/B1/A2/B2/A3/B3 fresh alternating processes, each 6000 warm/12000 measured
  offers/admissions/unique terminal successes at 200/s, zero refusals/unresolved.
  Three fresh ASYNC runs each 120 measured successes at 50 offers/s. All nine
  supervised processes exit 0, no remaining live group members. Exact p50/p95/p99,
  useful successes/s, allocations/success, post-GC heap and process populations are
  in the report run table and immutable `comparison.json`; no P07 numbers reused.
- Three bounded `short_profiles.py` HTTP executions through 120s supervision:
  none=7 cases/331 snapshots/19.098379s; all=8/343/20.467105s;
  sync-queue,runtime-config=8/329/18.878098s. Total 23 cases/1003 observations.
  Partial T3 divergent waiters, keyed shared success/replay and offload false/true;
  T4 retry/expiry/non-cooperative backend and toggle during work; T5 remove/re-register
  with late old response; T7 256KiB payload/output and >1MiB refusal; T9 physical
  drain and post-policy GC. Peaks per selection: live1/keys3/outcomes6/reservations1,
  canonical and copy bytes524802 each, waiters2/open-futures1. Final observed public
  ownership and backend-active counts drain to zero, without inferring alias zeros.
- `CONTROL_PLANE_MODULES=container-deployment-provider NATIVE_BUILD_MEMORY=8g NATIVE_PARALLELISM=4 timeout 600 scripts/native-java-image.sh control-plane nanofaas/control-plane:p19-6d083033-container`
  from exact B source archive: managed native build exit0, actual Docker provisioning201.
  Image `sha256:9d3dc1671abff05834223bdd47c69be5e4630beadb9cc134f5dfd19be658fe1c`.
- `native_smoke.py /tmp/nanofaas-p19-r1.9kgN9P none` and `... container`:
  **both exit1, invocation HTTP500**, missing native reflection record components
  for `InvocationResponse`; replay unreached. Both shutdown logs also contain missing
  reflection registration for `ThreadPoolExecutor.shutdown()` on wake-up scheduler.
  Container stop/removal exit0 and no remaining scoped smoke containers do not make
  native application shutdown green. Docker-java adapter selected explicitly because
  Distroless lacks CLI; corrected managed run still fails on the same runtime defect.
  Minimal image `sha256:e5cd87f4e0d6c0a12c9a8a9563e0cb4d138682ef38b1b52c35774b48910d76af`.

Still open: T6 blocked-provider/wakeup, T8 each SDK process, complete proxy/callback/
slow-client numerical ownership and multi-destination churn. These are timeboxed
evidence gaps, not unavailable-infrastructure exceptions or replaced by tests.
Independent future aliases/retry handles/offload subscriptions remain explicitly
unavailable. Existing G1–G18/R1–R8, SDK and JVM package results are inherited unchanged
with original timestamps, not rerun this round. Native failures require owning-task
TDD and agreement on revising exact B; no production fix was silently folded in.
Observer overhead attribution unavailable; allocations include observer cost.
Brief native diagnostic smoke overlapped A2, host affinity is not exclusive, and
three repeats establish neither equivalence nor peak capacity or soak behavior.

New immutable dossier schema v2, BLOCKED, accepted_control=null:
`p19/dossiers/70ea3494d86e6435ac2de1cbb207311079135f129c573af8f45d09968bfd66d0`.
Manifest SHA-256 `70ea3494d86e6435ac2de1cbb207311079135f129c573af8f45d09968bfd66d0`.
Freeze and `dossier.py verify` each exit0: VERIFIED, 608 payload files. Integrity
is not acceptance. Previous immutable dossier unchanged. Raw runs, short profiles,
native failures, sources/build identities, compiled observer, exact commands,
red-green logs and inherited regressions are included; final graph gates live
outside it to avoid a self-referential digest.

GitNexus exact-symbol pre-edit impacts and consumer contexts retained under graph/;
LOW/UNKNOWN, no HIGH/CRITICAL. UNKNOWN resolved with text search. Prior retrospective
probe-main impact debt remains disclosed. Fresh index: 24.9s, 22,372 nodes/63,147
edges/763 flows. Complete all/staged precommit gates, self-review, exact commit
identity and full run table are in the fix-round report. Protected SHA audit passes
for both overload files, all six skill directories and the snapshot test; production
diff against B remains empty. Commit scope is only P19 measurement/evidence/docs.

## P19 fix round 2 — native metadata fix, validation in progress

User authorizes a new B product revision. Strict systematic debugging traced
completed invocation→erased response→Jackson record serialization, and native
shutdown→DisposableBeanAdapter→concrete executor shutdown reflection. Existing
catalog binding and explicit JDK hint patterns were reused. New focused regressions
were RED before production changes (7 tests/2 failures, exit1); response-only hint
made one GREEN while scheduler remained RED; exact native parent shutdown target
was separately RED. Minimal record binding and two exact shutdown-method hints
then passed the new tests plus catalog/scheduler regressions (Gradle exit0).
No scheduler algorithm/ownership change. New B is the product-fix commit containing
InvocationLifecycleRuntimeHints, its application import and InvocationNativeHintsTest.
Native rebuild/smoke, missing bounded numerical profiles, fresh R1–R8 and alternating
A/B remeasurement remain pending; no accepted control is declared at this stage.
Full commands, expected failures and graph evidence are in the round-2 report.

### P19 round 2 — executed, BLOCKED for re-review

Product B is now `b4770d6675adc99f53444260dcf0200f10b517cf` (native binding metadata
only); A remains diagnostic. Native TDD RED 7/2, intermediate 7/1, GREEN 46/0/0;
both rebuilt minimal/managed images pass real invocation/replay/removal/shutdown
twice, final stop/remove exit 0 and no native reflection/destroy errors. Finding 2
closed. Fresh R1–R8 green: G1 7/0/0, G14 68/0/0 including R6; G11 85/0/0.
Portable contracts 27/0/0; five JVM selections rebuilt. Unaffected prior groups are
explicitly inherited, not mislabeled fresh.

Fresh A1/B1/A2/B2/A3/B3 all exit 0, each 12000 offered/admitted/unique terminal
successes at fixed 200/s, plus three B ASYNC runs of 120. Complete latency,
allocation/post-GC and owner records retained. No native/profile overlap in this
campaign. Observer overhead remains unavailable; no peak-capacity/equivalence claim.

All missing bounded surfaces executed: real blocked provider/shared wake-up with
healthy traffic, three-destination churn, actual proxy/large payload/slow clients,
and all five actual SDKs in two fresh process generations with non-cooperative
handlers and actual callbacks. Proxy, SDKs and three configured short profiles
exit 0; observed owned work/bytes drain; no live child remains.

**Finding 1 remains open:** latest strict managed profile exit 1, three empty pool
registry entries after 4s policy wait plus bounded 10s drain, despite zero physical
connections/acquisitions/function mappings/backend work. Earlier partial passes
do not waive it. Root cause not established. Per user timebox stop code changes,
freeze a BLOCKED candidate with accepted_control null, send failure to re-review.
Missing wire status/private owners remain unavailable, not invented zero.

Full exact commands/counts, run table, limits and RED/GREEN/graph/preservation
evidence are in task-P19-report.md and the new dossier (closing digest below).
Protected dirt untouched; no P20b; no subagents.

Round-2 candidate dossier:
`p19/dossiers/cc5e971f0e354b36ca7238aef1b5831c2b994e9a95eceb31b3770f267ac14a4d`;
manifest SHA-256 `cc5e971f0e354b36ca7238aef1b5831c2b994e9a95eceb31b3770f267ac14a4d`.
698 payloads, 63,768,471 bytes, schema v2, BLOCKED/accepted_control null.
Freeze+separate verify exit 0, 699 independent SHA256SUMS checks green; archived
jar hashes and exact A/B source archives verified. Frozen harness 27 tests green
with repository directory layout (shallow extraction import limitation disclosed).
Complete all/staged GitNexus HIGH aggregate risk reviewed: final 82/81 symbols,
11 flows, no partial/truncated/UNKNOWN verdict. Protected staging/hash audit green;
all 19 process groups and native smoke containers absent. Raw RED XML whitespace
warnings preserved; non-dossier staged whitespace check exit 0. Full evidence and
remaining pool-drain failure are in the report; no accepted P23 control yet.

Round-2 commits: product/native fix `b4770d6675adc99f53444260dcf0200f10b517cf`;
profiles and frozen candidate `3345396ed51e8c070629c0d2d4fc1677526f04dc`.
Post-commit status preserves only the protected pre-existing dirty/untracked
targets. This documentation receipt does not change source B or artifact hashes.

### P19 fix round 3 — P12 identity root cause and TDD

The remaining drain failure is a stale owner/metrics registry, not live Netty
sockets: 1.3.6 disposeWhen deregisters with the supplied unresolved numeric address,
while registration used a resolved address. Netty removes the actual pool, but
InetSocketAddress equality prevents removal of the P12 key. The new real HTTP
regression proves Netty channelPools empty/physical counts zero before RED owner
count expected0/actual1 (Gradle exit1). Existing localhost tests miss this boundary.

Minimal fix normalizes only PoolKey socket-address identity without DNS, retaining
pool name/ID/host/port. No lifecycle bypass, counter clearing, tolerance increase
or profile weakening. GREEN P12/property/architecture: 25/0/0, exit0, with same-
endpoint recreation also checked. Exact upstream impact LOW (two registrar
callers); UNKNOWN test entrypoints resolved with rg. Protected dirt unchanged.
Final B identity, fresh managed/R1-R8/A-B campaign and accepted dossier are pending;
no P20b and no subagents. Full RED/GREEN/source evidence in task-P19-report.md.
## P19 fix round 3 — accept the corrected P12 owner control (2026-09-11)

Status: **DONE_WITH_CONCERNS**. This entry supersedes the round-2 managed-pool
blocker; earlier immutable evidence is preserved. No P20b or subagents.
Product commit/final B: **d93b68cdf1cca6e7af17e1c641c8e236c80d0fca**,
`Normalize dispatch pool registry addresses`. Diagnostic A remains
`61d72e73528db62cf8ca465c6a037981d7ec13b0`.

Root cause: Reactor Netty 1.3.6 actually disposes/removes its pool by host/port,
but deregisters metrics using disposeWhen's unresolved address argument. Numeric
registration was resolved; P12's exact InetSocketAddress key equality missed
removal and retained the already-closed pool metrics graph. The eight-line private
PoolKey constructor normalizes to unresolved host-string/port without DNS,
preserving pool name/id/host/port. No counter masking, transport policy change,
tolerance increase, production reflection or unrelated refactor.

TDD: real numeric-loopback regression first fails **expected0/actual1** only after
asserting Netty's channelPools map is empty and physical counts are zero (exit1,
one expected focused failure). After the minimum fix, pool/property/architecture
selection passes **25/0/0** (exit0), including same-endpoint recreate/drain.
Exact upstream PoolKey impact before edit: LOW, registerMetrics/deRegisterMetrics
callers; UNKNOWN test entrypoints resolved with rg/JUnit selection. Raw impact,
Netty bytecode, RED/GREEN XML and command logs are in the new dossier.

Fresh final-B gates: `python3 H/verification.py W/verification G1 G10 G14`, then
G6 after comparison: exits0, respectively **7/41/68/73 passed**, **0 failures,
0 skips**, total **189**. R1-R4/R7/R8 in G1, R5's exact unowned-slot test in G6,
R6 in G14. Final audit corrected an intermediate erroneous claim that G1 covers
R5; inherited G6 is preserved separately and does not count as fresh evidence.
Portable contracts **27 passed**, observer future contract passed; allocation
collector validation observed 67,155,880/67,430,808 bytes for 64MiB escaped
platform/virtual allocations after thread exit. Five B JVM selections rebuilt
with explicit final revision metadata. SDK/profile launchers rebuilt; actual
managed/proxy/five-SDK/none/all/sync+runtime-config profiles all valid on final B.

Managed strict assertion unchanged: **22 offered/admitted, 16 useful successes,
6 expected blocked-provider terminal errors, 0 refused**. Twelve churn names,
three destinations, twelve same-key replays not counted as new work. Registry
**peak2 -> in-process drain0**; wake-up gate/timer **2 -> 0**; physical pools,
pending acquisitions, destination mappings and backend work zero at drain.
Proxy: 11 ordinary offers/9 admissions/2 refusals/8 successes/1 expected error;
active exchanges **2 -> 0**, buffered bytes **12,591,169 -> 0**. Slow clients
separately accounted; missing headers mean unavailable admission/refusal, not zero.
All five actual SDKs have two fresh generations, numerical owners and callbacks.
Selected owner census, per-case work and explicit unavailable/not_applicable
owners remain in raw profile records; never sum reflective aliases.

Native images rebuilt from exact final-B source export, both build exits0:
minimal `sha256:0b4e4a3367d4dfb402a69cf0ec5ed8e3bf4e682763dc5f595cf28b47ebb161b6`,
managed `sha256:3a1dfa82e3ff33739604fa1ce4740f324d0779666a736ddf26dbfa139bb43dbc`.
`python3 H/native_smoke.py W none container`: exit0, actual invocation/replay same
execution ID, function removal, graceful stop/container removal, no native errors.
Existing Docker docker-java route used; Kubernetes/native is not claimed green.

Fresh `taskset -c 5-8 python3 H/campaign.py W`: exit0, **837.009s**, six alternating
common HTTP fresh processes plus three ASYNC. No builds/tests/native/SDK profiles
overlap. Fixed offered/admitted/completed 12,000 per common run, 120 per ASYNC;
zero refusals/errors/unresolved. Units: ms for p50/p95/p99, KiB allocated per
unique success, MiB used heap after verified GC at removal (other three GC phases
and full raw per-run records also archived).

| Run | Useful successes/s | p50/p95/p99 ms | KiB/success | Removed heap MiB |
|---|---:|---|---:|---:|
| A1 | 199.997 | 2.141/3.237/3.805 | 135.07 | 38.70 |
| B1 | 199.954 | 1.501/3.067/3.745 | 140.46 | 39.70 |
| A2 | 199.994 | 1.442/3.133/3.659 | 134.97 | 38.45 |
| B2 | 199.946 | 1.484/3.122/3.742 | 140.11 | 39.60 |
| A3 | 199.998 | 1.431/3.165/3.657 | 134.60 | 38.67 |
| B3 | 199.996 | 1.208/1.799/2.143 | 140.36 | 39.53 |
| B-async-1 | 50.169 | 10.504/13.719/14.176 | 1108.94 | 24.38 |
| B-async-2 | 50.186 | 10.570/14.015/14.585 | 1109.89 | 24.57 |
| B-async-3 | 50.194 | 10.522/13.416/13.968 | 1115.68 | 24.39 |

B/A allocation changes +4.00/+3.81/+4.28%; p95 -5.25/-0.35/-43.18%; p99
-1.56/+2.27/-41.40%. B3 variability is retained, no equivalence or peak-throughput
claim. P00 is diagnostic only; P23 uses this corrected B and reruns contemporaneous
B/C. Observer overhead unattributed, nonexclusive host affinity, unobservable
aliases and short-profile/not-soak scope remain concerns, not invented zeros.
Unchanged gates carry explicit transitive inherited provenance.

Accepted immutable **nanofaas-p19-dossier-v2**, final B above; manifest/directory
SHA-256 **74a0d632f3373724032df4719945ba50e84f827e49773c46b94902b9c4809dc0** at
`docs/experiments/lifecycle-memory-2026-09/p19/dossiers/74a0d632f3373724032df4719945ba50e84f827e49773c46b94902b9c4809dc0`.
652 payloads / 58,174,842 bytes plus manifest/checksum list. Freeze and independent
verify exit0; all 653 checksum entries OK; extracted frozen harness **27 contracts
pass**, measured jar SHA and source PAX revision bindings independently match.
All 19 owned process groups empty, supervisor exits0, no kill fallback; native smoke
containers absent. Protected SHA-256 audit unchanged. GitNexus complete all-change
pre-freeze check LOW, 9 files/6 symbols/0 flows; FTS warning/stale prose supplemented
by text/diff/hash inventory. Final staged audit/commit receipt follows in the report.

H=`docs/experiments/lifecycle-memory-2026-09/p19`, W=`/tmp/nanofaas-p19-r3.QxIGIR`.
Full commands/results/limits and commit receipts:
`.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P19-report.md`.
Round-3 delivery commit: **58c56941aec6487f12a939c935b6aa54371a05ba**,
`Freeze corrected P19 control`, 663 scoped files including all 654 dossier files;
protected targets absent. Product B remains **d93b68cdf1cca6e7af17e1c641c8e236c80d0fca**.
Final complete GitNexus confirmation before delivery: all 200 text/indexed files /
8 symbols / 0 flows LOW; staged 198 / 7 / 0 LOW; no partial/truncated/UNKNOWN.
Earlier 9/8-symbol counts changed only in stale STATO prose section mapping after
the audit text addition. A wrapper equality assertion failed on that difference;
the commit orchestration continued. Full results were then read and code-symbol
sets confirmed unchanged; no missing/truncated graph result or runtime change.
This post-commit inspection timing is disclosed, not called a clean equality check.
Whitespace check exit2 only for immutable raw RED XML/verbatim requirements;
excluding dossiers exit0. Final artifact verification/preservation remains green.
