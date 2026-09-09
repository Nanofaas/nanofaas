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
