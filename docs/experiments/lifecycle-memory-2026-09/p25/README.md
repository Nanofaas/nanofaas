# P25 — Campaign closure: coverage, limits and hand-over

**State: campaign INCOMPLETE.** This document is the closure deliverable P25 asked
for. It publishes the per-row coverage table, the limits that remain, the checks
that were run directly for this closure, and the exact commands that are still
missing. It does **not** declare the campaign finished, and no row below is closed
by intuition alone.

Plan: [2026-09-08-control-plane-lifecycle-memory-and-modularity.md](../../../plans/2026-09-08-control-plane-lifecycle-memory-and-modularity.md).
Ledger: [../STATO.md](../STATO.md). Line references of the form `STATO:NNNN` point
at that ledger. Review that opened the campaign:
[docs/control-plane-pre-soak-review-2026-09-08.md](../../../control-plane-pre-soak-review-2026-09-08.md).

---

## 1. Coverage table

Final state per row, one row per row of the plan's coverage table (plan §3).
Column "Evidence" names the proof, not the intention.

| Review finding / area | Task | Decision or fix taken | RED→GREEN evidence | Measurement | Final state |
|---|---|---|---|---|---|
| R1 outcome weight, count/byte cap semantics | P02 | `OutcomeWeigher` rewritten: cacheable/non-cacheable verdict, bounded traversal, saturating arithmetic, `freeze`; `ExecutionStore.settle` declines non-cacheable outcomes | `R1OutcomeByteBudgetRegressionTest` RED→GREEN (STATO:264-266); `ReviewAccountingRegressionTest` in the review round (STATO:814) | 30×1 MiB and 256-nulls+1 MiB now declined (STATO:304); ordinary-path 130.1→121.9 ns/op, 776→440 B/op (STATO:863) | **VERIFIED** |
| R2 archive/key/replay window | P04 | `ExecutionLifecycle.settle` owns the terminal transition; key protection precedes archive and live removal; `claimIfMatches` re-claims only abandoned bindings | `R2ArchiveEvictionReplayRegressionTest` RED→GREEN (STATO:455); `ReviewLifecycleGateTest` (STATO:814) | none recorded | **VERIFIED** |
| R3 waiter timeout alters the shared outcome | P01, P05 | Per-waiter timeout decided in ADR 0001; P05 removes the shared-record mutation from a single waiter's timeout | `R3WaiterTimeoutSharedOutcomeRegressionTest` RED→GREEN (STATO:562); `ReviewOffloadWaiterBudgetTest` (STATO:786) | full core 578/3 (STATO:583) | **VERIFIED** |
| R4 offload and other terminal exits | P04, P05, P06 | Offload completion routed through the lifecycle; `completeOffloadedExecution`/`failOffloadedExecution` finalize unconditionally | `R4OffloadCompletionAfterWaiterTimeoutRegressionTest` RED→GREEN (STATO:562) | none recorded | **VERIFIED** |
| R5 sync disabled releases an unacquired slot | P06 | Generation-scoped `DispatchLease`; lease held until a non-interruptible handler ends | `R5DirectCompletionUnownedSlotRegressionTest` RED→GREEN (STATO:696); `QueueLeaseGenerationTest` in both queue modules (STATO:819) | "accepted + released == acquired, no negatives" (STATO:744) | **VERIFIED** |
| R6 partial deprovision loses state and proxy | P08 | Confirmed-then-forget removal, `PartialDeprovisionException`, `holdPendingRemoval`, 409 `FUNCTION_REMOVAL_PENDING` | `R6DeprovisionFailureOwnershipRegressionTest` RED at line 128 → GREEN (STATO:1185); fresh gate G14 68/0/0 (STATO:3313) | none recorded ("P08 adds no limit", STATO:1236) | **VERIFIED** |
| R7 concurrent maxKeys overshoot | P03 | CAS reservation on `AtomicLong occupied`; loser returns its reservation; `put` throws at cap | `R7KeyBudgetAtomicityRegressionTest` (16 admitted → 1) RED→GREEN (STATO:348) | focused 27/0; full 565/6 (STATO:364, 371) | **VERIFIED** |
| R8 name history in metrics/snapshots; offload metrics | P09, P10 | `Metrics` keeps only current registrations; offload owners tied to generation; `admittedGeneration` fence on completion counters; `ReplicaStatusSnapshot.invalidate` detaches | `R8HistoryCleanupRegressionTest` both assertions RED→GREEN (STATO:1279, 1334); fresh G1 7/0/0 (STATO:3313) | none recorded | **PARTIAL — see §2.1** |
| Direct admission; live-work/queue/waiter bytes | P06, P07 | `ResourceOwner`/`ResourceQuota`/`ReservationBatch`; `IngressBodyLimitWebFilter` + `RetainedInputEstimator`; `InvocationCapacity` reserves count+bytes in one rollback batch | `ResourceQuotaTest` 4 RED→GREEN (STATO:1631); `IngressBodyLimitHttpTest` 3-of-4 RED→GREEN (STATO:1733); `DirectAdmissionWithoutQueueBoundedRegressionTest` RED→GREEN (STATO:690) | 100 concurrent direct calls admit exactly 1, refuse 99 (STATO:740); P07e T1 244,226/s vs P00 376,250/s (STATO:1988) | **VERIFIED, with a disclosed cost** |
| Refresh executors and loop-blocking reads | P10 | Two owned bounded pools with `AbortPolicy`; `observe`/`refresh` split; unavailable cycles skip scaling/governing | `ReplicaStatusSnapshotTest.refresh_releasesTheCallerAtItsDeadlineWhenTheProviderNeverAnswers` RED→GREEN (STATO:1521); items 1/2/3/5 explicitly **not** claimed RED (STATO:1526) | 201 submissions → 1 active / 2 queued / 198 rejected (STATO:1535) | **VERIFIED (targeted)** |
| Wake-up timers and repeated GETs on ready functions | P11 | `DeploymentWakeUpGate` one owner per generation; bounded coordinator; warm path requires a FRESH ready observation; owned scheduler with remove-on-cancel | 37 focused gate/coordinator tests green (STATO:2106); **no test class named in the ledger** | 2,000 warm calls → no forced GETs (test assertion, not a measurement) | **VERIFIED** |
| Dispatch cancellation at administrative expiry | P06 | `handleAdministrativeExpiry` cancels the real transport handle; DEPLOYMENT forwards cancellation without a shared wake-up | `DispatchLifecycleAndCancellationTest` 13 tests, HTTP cancellation at a real MockWebServer (STATO:681) | exactly-one-of-100 bound (STATO:740) | **VERIFIED** |
| HTTP pool per destination and endpoint churn | P12 | One context-owned `DispatchConnectionPool`; 30 s idle, lifetime disabled, 5 s eviction; `disposeWhen(SocketAddress)` | `DispatchConnectionPoolTest` et al. reported green, **no RED baseline** (new API, STATO:2247) | 200 ms inactivity rotates 16 destinations to ≤1 within 5 s; allocation returns to baseline (STATO:2268) | **VERIFIED, later corrected in P19** |
| Container proxy buffer and deadline | P13 | Bounded buffering (2 MiB request / 4 MiB response / 32 MiB aggregate), three phase deadlines, 413/502/503 | `P13BoundedProxyTest`; compile RED, growth RED, deadline-separation RED→GREEN, and a **full-suite RED** for retained `Snapshot` after `close()` (STATO:2308-2342) | largest canonical input 440,571 B; 1 KiB loop 3,390.4 µs/call, 295.0 calls/s (STATO:2298) | **VERIFIED** |
| WaitEstimator timestamps | P14 | Buckets **rejected**; exact timestamps retained with declared caps and rotation-queue cleanup | per-case RED→GREEN; no test class named in the ledger (STATO:2463) | predeclared tolerance; boundary burst exact=0 vs bucket=100 at 30.999 s → buckets rejected; 2.90% relative wait error (STATO:2450) | **DECIDED BY MEASUREMENT — see §2.3** |
| Catalog copies and persistence | P15 | Snapshot catalog kept; durable desired replicas persisted before the provider call; volatile observations never serialized | two P15 test classes; per-round RED→GREEN (STATO:2524, 2564, 2620, 2652) | 12-row matrix; 1,000 entries max 10.723 ms ≈ 0.214% of the 5 s autoscaler period (STATO:2496) | **DECIDED BY MEASUREMENT** |
| SDK callback bytes; Python; Java-lite | P16, P17, P18 | One cross-language policy (32 handlers, 1 MiB in/out, 2 MiB callback, 128 pending, 3 attempts); Python runtime-owned bounded executors; Java-lite owned executors and idempotent stop | per-SDK suites green at close: Java 143/143, Java-lite 81/81, Python 139, Go 116 normal + 116 race, JavaScript 80/80 (STATO:3013, 3046); P17/P18 CLEAN re-reviews (STATO:2888, 2993) | runtime-backed RSS only, explicitly not production capacity (P16b report) | **PARTIAL for JavaScript — see §2.2** |
| Lifecycle/capacity ownership; port separation | P04–P07, P20 | `GenerationPhase` protocol; small ports (`InvocationEnqueuer`, `RetryScheduler`, `InvocationDispatch`, `DispatchCapacity`, `QueueLifecycle`, `InvocationObservations`); temporary adapters removed | `LifecyclePortsTest` rules failed in **both** queue modules with zero compile errors → GREEN (STATO:3427); governor cycle race RED→GREEN (STATO:3432) | none recorded; 350 suites / 1924 tests / 0 failures (STATO:3481) | **VERIFIED** |
| SPI, module dependencies, RuntimeConfigExtension | P21 | `:control-plane-spi` extracted as a mandatory module; 46 contracts; `RuntimeConfigExtension` relocated | `SpiPurityTest` verified falsifiable with a controlled Spring reference (STATO:3625); profile matrix, each a separate invocation (STATO:3618) | none recorded; 351 suites / 1927 tests / 0 failures (STATO:3616) | **VERIFIED** |
| Deployment/refresh beans and shared observations | P10, P11, P20, P22 | `DeploymentReadiness` port; `ManagedDeploymentOrchestrationAutoConfiguration` conditional on a provider; per-module ArchUnit rules | `MinimalProfileBeanOwnershipTest` RED→GREEN (STATO:3696) — **but see §2.4** | none in the P22 entry; P23 measured the defect's cost as ~1% allocation (STATO:3770) | **PARTIAL — see §2.4** |
| Boundary protection, performance, packaging/native | P19, P21–P23 | P19 froze an accepted control dossier; P23 ran the section-8 matrix and fixed the P22 defect | G1/G6/G10/G14 fresh gates 7/73/41/68 passed, 0 failures (STATO:3311); 18 positive + 4 negative matrix rows (STATO:3778) | B/C pair: 200.00 useful/s both arms, p95 −4.16%, allocation −0.77%, gate breaches `[]` (STATO:3753) | **VERIFIED (DONE_WITH_CONCERNS / DONE_WITH_LIMITS)** |
| Soak and original RAM attribution | P24 | Candidate diagnostic soak measured; JS abort-listener retention fixed in `b59dc152`; harness record-limit bug found and fixed | `runtime.handler-retention.test.ts` (success and failure); harness regression test added | JS rss-return **PASS** 67.273→61.325 MB; control-plane and Java rss-return **FAIL**; live data flat at 28.75 MB (STATO:3966) | **DEFECT CLOSED, NOT QUALIFIED — see §2.5** |
| Documentation and verifiable closure | P25 | This document | — | — | **DONE (campaign incomplete by declaration)** |

---

## 2. Rows that are not green, and why

### 2.1 P09 — implemented-partial

R8 itself is fixed and green, in isolation and in fresh full-profile gates
(`R8HistoryCleanupRegressionTest`, both assertions; G1 7/0/0). What is not closed
is the task: its last recorded verdict is a review **"Needs fixes — spec ❌ on
part of acceptance criterion (b)"**, and the scoped re-review the fix round
requested is **not recorded as having returned** (STATO:1381, 1464).

Two further gaps the ledger states itself: offload completion captures no
admission generation, so its fence is "a real, disclosed limitation, not a false
claim of closure" (STATO:1352); and a reproduction test for Important finding #2
was **deleted** because it could not be made RED on the real baseline
(STATO:1385-1432). Deferred minors remain: ~110 lines of dead legacy offload
meter code, a narrow `RemovalFence` construction race in
`SyncQueueService.removeFunctionState`, and nondeterministic registration-listener
ordering (STATO:1442). P23 carried these forward explicitly as "the P09/P10
deferred minors" (STATO:3798).

### 2.2 P16 — its acceptance criterion was contradicted for JavaScript

P16's acceptance requires that pending bytes and counts "tornano a zero dopo
drain/stop; nessuna Promise/task/callback viene lasciata senza owner"
(plan:734-736). For the JavaScript SDK that was **not true when P16b closed**, and
it stayed untrue for three days.

Independently verified for this closure rather than taken from the ledger:

| Commit | `removeEventListener` in `sdks/javascript/src/runtime.ts` | Removes the composite-signal listener? |
|---|---|---|
| `7f8f4c2e` (P16b parent) | 0 | the `{ once: true }` listener is already present, line 314 |
| `1432cc5d` (P16b close) | 2 (lines 267, 529) | **no** — neither touches it |
| `HEAD` | 3 | yes, line 720 — added by `b59dc152` |

The retained population measured at the time: 25,655 `Timeout` and 25,637
`Listener` objects post-GC, heapUsed 120.5 MB; after the fix 3 `Timeout`, no
`Listener`, heapUsed ≈ 6.6 MB
([p24/diagnostic-fixes-2026-09-13.md](../p24/diagnostic-fixes-2026-09-13.md)).

The ledger does not reopen P16 for this; the P24 entries treat it as a product
defect found by measuring the SDK process separately. That is a defensible
attribution, but the P16 row cannot be read as "acceptance met at close" — it was
met only later, by a commit whose subject ("Add P24 soak instrumentation") does
not mention the SDK fix.

### 2.3 P14 — the measurement in the ledger is not the one in the repository

P14 was decided by real measurement, and the decision (buckets rejected) is
sound. But the numbers quoted in the ledger match the task report under
`.superpowers/sdd/2026-09-08-.../task-P14-report.md`, **not** the tracked
`../P14WaitEstimatorMeasurement.out`, which holds a later rerun with different
values (`exactNs=402/245` for one function and `1125/1038` for 1,000, against the
`302/202` and `920/990` the ledger quotes; `afterIdleExact=0+0` in every
workload). Both are real runs. Anyone citing "the P14 measurement" must say which
one, and the two are not interchangeable.

### 2.4 P22 — its claim was proved false by P23, after P22 closed

P22 recorded that the managed orchestration was wired conditionally and that the
minimal profile built none of it. P23 proved that false for the component-scan
path: the first `C` jar built the entire managed orchestration under
`modules=none` and built no immediate readiness — "contradicts what P22 recorded"
(STATO:3726).

The cause: `ManagedDeploymentOrchestration` carried `@Configuration` inside a
component-scanned package, so the scan registered it unconditionally and the
`@ConditionalOnBean` on the importing auto-configuration was never consulted
(STATO:3668).

It was **found by a packaged run, not by any test**, and fixed **after P22's
closure**, in the P23 commit `b2aed0a3`. P22's own test passed throughout because
`ApplicationContextRunner` performs no component scan — it proved the condition
evaluates correctly, not that the beans are reachable only through it
(STATO:3738). P22's entry records no full-suite count, so its own profile gate is
**UNKNOWN**.

### 2.5 P24 — the defect is closed, the profile is not qualified

Measured 2026-09-19 on the candidate revision, and re-evaluated from the saved
evidence with the real harness:

| Criterion | Result |
|---|---|
| control-plane cgroup budget | PASS |
| control-plane rss-return | **FAIL** — 224.526 MB → 496.435 MB, tolerance 0 |
| word-stats-java cgroup budget | PASS |
| word-stats-java rss-return | **FAIL** — 249.258 MB → 251.851 MB |
| word-stats-javascript cgroup budget | PASS |
| word-stats-javascript rss-return | **PASS** — 67.273 MB → 61.325 MB |
| sample integrity | PASS |

Overall FAIL, `p24_qualified: false`. The two JVM failures are committed heap, not
retained state: `jvm_gc_live_data_size_bytes` is 28.75 MB across 984 samples and
42.17 MB in the single fresh reading the final forced full GC produced, against a
constant `jvm_gc_max_data_size_bytes` of 501.9 MB and a resident set ending at
496.4 MB. Because `evaluate.py` hard-codes the `baseline` phase as the reference
and `baseline-diagnostics` completed in 0.0 s, no warm post-collection reference
exists in the saved evidence — so the criterion cannot be satisfied for a JVM
without a new measurement, and the frozen policy is left untouched.

`run-coverage` is INCONCLUSIVE: the crashed run never wrote
`acceptance-manifest.json`.

---

## 3. Real remaining limits

These are contract limits and attributed residuals, not reproducible leaks left
open. Each is stated by the ledger; none is inferred here.

1. **A JVM role cannot return its resident set to a cold baseline.** Committed
   heap grows to `Xmx` and is not released. The frozen `return_to_reference`
   criterion is unsatisfiable for JVM roles without a heap-release mechanism or a
   warm reference.
2. **The Go SDK suite is not green in the last verification pass.** "Not run,
   therefore not passed: toolchain `go1.24` is not installed and cannot be
   downloaded offline. Recorded as an environment limitation" (STATO:3791).
3. **Non-cooperative work cannot be killed.** A Python thread, a Java-lite
   virtual thread or a Go handler that ignores interruption outlives its stop
   deadline; it is reported and remains owned, never force-killed (STATO:2903,
   2966; P16b report).
4. **Quotas bound the control plane, not the runtimes.** They do not bound RSS,
   native memory or remote memory (STATO:1840).
5. **A function left in pending removal across a restart** is reconciled and
   serves again under a new generation (STATO:1255).
6. **Kubernetes deprovision is unchanged** and still throws raw exceptions, so it
   keeps restore-and-reconcile (STATO:1252).
7. **The P07e cost is real.** Throughput −35.1%, p50 +103.0%, allocation +129.4%
   against the P00 diagnostic (STATO:1988); P19 later bounded the campaign-wide
   cost at ≈ +3.5% allocation (STATO:3773).
8. **Open P09/P10 minors and one non-reproducing observation**:
   `P07dWaiterAdmissionTest.divergentDeadlinesDetachOnlyTheShortWaiter` failed
   once, passed 5/5 in isolation, and is recorded as open — "a recurrence should
   be treated as a real race, not as noise" (STATO:3630).
9. **Two pre-existing defects P20b reproduced and deliberately left**, outside its
   change surface: async removal drain concluding a still-running reservation as
   `FUNCTION_REMOVED` (the sync module deliberately does the opposite), and
   `ExecutorBackedInvocationEnqueuer` dispatching outside the failure-cleanup
   wrapper (STATO:3529).
10. **P14's two datasets are not interchangeable** (§2.3).
11. **`control-plane-spi` and `control-plane` share package names across two
    jars.** Legal only because no `module-info.java` exists; a future JPMS
    migration would have to unsplit them first (STATO:3610).
12. **No soak or E2E for the whole profile pair.** "Three repeats do not establish
    statistical equivalence and none of this replaces the soak" (STATO:3794).

---

## 4. Checks run directly for this closure

Independent of the ledger, on the current tree:

| Check | Result |
|---|---|
| `@Disabled` / `@Ignore` in Java tests (`platform/`, `services/`, `sdks/`) | none |
| `pytest.mark.skip` / `@unittest.skip` in `experiments/` | none |
| TODO/FIXME/HACK/temporary-adapter markers in production Java | none (matches were `ToDoubleFunction` and a local `temporary` path variable) |
| Benchmark or experiment config active in `platform/control-plane/src/main/resources/application.yml` | none; line 117 is a comment citing a measured decision |
| NanoLab / P24 / soak residue in `resources/` or `deploy/` | none |
| Pre-existing experimental files altered by the campaign's 23 commits | none — zero files under `docs/experiments/` outside the campaign directory |
| Working tree | clean, no untracked files |

**Three-profile baseline, run on the current tree** (the profile separation matters:
each profile needs its own Gradle invocation, since module selection changes the
classpath):

| Profile | Command | Result |
|---|---|---|
| async-queue, runtime-config | `:control-plane:test :control-plane-modules:async-queue:test` | BUILD SUCCESSFUL, 1 m 26 s |
| sync-queue, runtime-config | `:control-plane:test :control-plane-modules:sync-queue:test` | BUILD SUCCESSFUL, 1 m 22 s |
| runtime-config only | `:control-plane:test` | BUILD SUCCESSFUL, 1 m 13 s |

Control-plane results in the async profile: 835 tests, 0 failures, 0 errors,
5 skipped (the skips are profile-conditional). Zero result files carry failures or
errors.

One operational note, recorded because it cost a discarded run: an earlier async
invocation failed with `java.nio.file.NoSuchFileException` on
`build/test-results/test/binary/in-progress-results-generic.bin` **while a second
Gradle build was running on the same checkout**. That is an output-directory
collision, not a test failure — no result file carried a failure. Builds on this
repository must be serialised; `--no-parallel` only bounds parallelism inside one
invocation.

**Why this matters given §2.2.** The campaign's own history records that its first
"clean" full-suite number, 613 tests / 0 failures, was obtained by **excluding
`R8HistoryCleanupRegressionTest` with an init script outside the repository**
(STATO:826). That exclusion is not in the repository today, and no test is
disabled now — but the episode is part of the record and must not be dropped from
the hand-over.

---

## 5. What is still missing

P25's fifth requirement: state exactly the command that is missing rather than
claiming completion.

1. **Paired P24 requalification.** No paired run exists, so neither revision is
   qualified as a pair.
   ```
   # NanoLab checkout, NANOFAAS_ROOT pointing at the revision under test
   ./nanolab.sh run packages/nanolab/scenarios-v2/memory-soak-sync-container.yaml \
       --environment packages/nanolab/environments/local.yaml
   ```
   to be run once at `e35405ee` and once at the candidate revision, each in a
   separate invocation, then evaluated with the fixed harness.
2. **A warm post-collection reference for the JVM roles**, without which the
   `return_to_reference` criterion cannot be satisfied. This was scoped during
   this closure and is **a harness change, not a policy edit**. What was
   established, in the order the constraints bite:

   - `evaluate.py` hard-codes `baseline` as the reference phase, so no
     declaration alone can compare two post-collection readings.
   - `CriterionPhase` already admits `diagnostic`, but `return_to_reference`
     is restricted to `phase: drain` (`config/soak.py:157`), so on the final
     checkpoint the only usable operations are `maximum` and `growth_review`.
   - The window for that checkpoint is recorded under `final_diagnostics`
     (phase names are hyphenated, then underscore-normalised), so a criterion
     naming `diagnostic` finds no window at all — the export filter in
     `runtime.py` lists `baseline`, `steady`, `drain` and would have to map it.
   - Decisively: `required_sample_count` floors at **2** samples
     (`acceptance.py:705`), and the diagnostic checkpoint yields **one**. A
     diagnostic-phase criterion is therefore structurally INCONCLUSIVE under
     the current sampling policy, whatever it is written to say.

   Fixing this means changing how the final checkpoint is sampled or how its
   window is derived — a change to the measurement harness that must be
   measured to be trusted. It cannot be validated in a session with no soak
   run authorised, so it is recorded here rather than half-applied. The
   untested plumbing that was tried for it during this closure was reverted.
3. **The Go SDK suite**, on a host with `go1.24` available.
4. **The P09 scoped re-review** of its fix round, which was requested and never
   recorded as returned.
5. **A reviewed decision on the JVM criterion** (§2.5). Until it is taken, both
   JVM rows stay FAIL and P24 stays unqualified.

---

## 6. Evidence

- Ledger, including the 2026-09-19 measurement entry: [../STATO.md](../STATO.md)
- P19 accepted control dossier: `../p19/dossiers/74a0d632f3373724032df4719945ba50e84f827e49773c46b94902b9c4809dc0/`
- P23 verification dossier: [../p23/README.md](../p23/README.md)
- P24 requalification: [../p24/README.md](../p24/README.md) and
  [../p24/diagnostic-fixes-2026-09-13.md](../p24/diagnostic-fixes-2026-09-13.md)
- Execution lifecycle ADR: [../../../architecture/0001-execution-lifecycle-contract.md](../../../architecture/0001-execution-lifecycle-contract.md)
- Harness fix, NanoLab: commit `8c4e360` — `describe_tree` / `_inventory_entries`,
  with the regression test; 2,645 tests pass.
