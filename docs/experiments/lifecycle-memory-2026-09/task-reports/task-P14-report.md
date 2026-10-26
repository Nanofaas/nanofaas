# Task P14 report — WaitEstimator retention and bucket measurements

## Status and revision

DONE. Work started from `3ee63002e325b7fbf2a376000e711e9cd3c20232` on
`control-plane-lifecycle-memory`. P09 was already complete; P13 did not modify
`sync-queue`. Unrelated dirty and untracked files were preserved and excluded
from staging.

## Implemented bounded exact estimator

`WaitEstimator` retains exact timestamps and preserves the existing inclusive
window cutoff, per-function minimum-sample selection, global fallback, queue-depth
zero result, admission behavior, and static `Retry-After` configuration.

Retention now has explicit hard bounds:

- 262,144 global samples;
- 262,144 total per-function samples;
- 32,768 samples for one function;
- 8,192 mapped function states; and
- 16 distinct cleanup candidates examined per estimator call.

The two exact deques therefore retain at most 524,288 timestamp nodes in total,
plus at most 8,192 function-state objects and one bounded cleanup-queue reference
per live/stale state generation. Samples over a cap discard the oldest exact
timestamp. This can only reduce the represented throughput for an already tracked
function and therefore makes estimated wait more conservative. At the function
cardinality ceiling, new functions retain the existing global-fallback behavior.

Both dispatch and admission/estimate calls advance a monotonic high-water mark.
Backward clock observations cannot reorder or resurrect samples. Cutoff subtraction
saturates at `Instant.MIN`, so `Instant.MIN`/`Instant.MAX` are safe. Exact
cutoff samples remain included.

Inactive states are visited through a rotation queue, never by scanning the map.
Dispatch and admission calls inspect at most 16 distinct candidates. The directly
queried function removes its own state when pruning makes it empty; removal is
conditional on the same state still being mapped and empty under its lock, so a
concurrent dispatch is retained and requeued. Explicit function removal clears its
history immediately. With no estimator calls at all, physical entries remain
bounded; the next calls exclude expired history immediately and retire all inactive
states within `ceil(states / 16)` calls.

## Strict RED → GREEN evidence

Initial RED:

```
./gradlew :control-plane-modules:sync-queue:test --tests '*WaitEstimatorRetentionTest' --console=plain --offline
```

failed at `compileTestJava` with 16 expected errors for the absent bounded
constructor, retention snapshot, and snapshot record.

The initial focused GREEN:

```
./gradlew :control-plane-modules:sync-queue:test --tests '*WaitEstimatorRetentionTest' --tests '*WaitEstimatorTest' --tests '*SyncQueueAdmissionControllerTest' --console=plain --offline
```

succeeded. Subsequent profiling review found and removed a linear
`ConcurrentLinkedDeque.size()` cap check and repeated processing of a lone cleanup
candidate; explicit counters and a one-rotation sentinel kept the suite GREEN.

Idle admission cleanup RED:

```
./gradlew :control-plane-modules:sync-queue:test --tests '*WaitEstimatorRetentionTest.admissionChecksAlsoDrainInactiveHistoryAfterIdle' --console=plain --offline
```

failed at line 45 because estimate calls did not run bounded inactive cleanup.
After adding bounded estimate-side maintenance, the focused suite succeeded.

Direct target cleanup RED:

```
./gradlew :control-plane-modules:sync-queue:test --tests '*WaitEstimatorRetentionTest.checkingAnIdleFunctionRemovesItsOwnExpiredState' --console=plain --offline
```

failed at line 57 because target pruning emptied its deque but left the map entry.
The minimal conditional empty-state removal was then added. The final focused
retention, legacy estimator, and admission suite succeeded in 4 s (39 actionable
tasks, 3 executed).

Tests cover empty windows, inclusive/exclusive cutoff boundaries, capped bursts,
prolonged idle cleanup through dispatch and admission, direct target cleanup,
explicit removal, backward monotonic clocks, and `Instant` overflow boundaries.
No test uses sleeping as an ordering proof.

## Predeclared bucket rule

Before measuring, the fixed contract was:

- 30 s estimator window;
- 1 s buckets and 31 slots, including the cutoff boundary bucket;
- count error no greater than one boundary bucket;
- no more than 10% relative wait error once the exact window has at least 10 samples;
- identical admission, fairness ordering, and `Retry-After` behavior;
- deterministic one-function and 1,000-function streams at 1/s and 1,000/s;
- burst, boundary, idle, removal, monotonic-range, and overflow streams;
- three warm-up and seven measured repetitions; and
- adopt only if correctness passed and retained state plus median allocation or
  dispatch cost improved.

The measurement source is
`docs/experiments/lifecycle-memory-2026-09/P14WaitEstimatorMeasurement.java`;
the captured result is `P14WaitEstimatorMeasurement.out`. It uses a thread-local
allocation counter with prebuilt names/timestamps and reports medians. It is a
controlled comparative micro-measurement, not a production capacity promise.

## Measurements and decision

```
comparison regularMaxAbs=9 maxEventsPerBucket=10 maxRelativeWaitError=2.90% boundaryExact=0 boundaryBucket=100 exactAdmit=false bucketAdmit=true fairnessOrderSame=true retryAfterChanged=false decision=retain-exact
workload=one-low  exactNs=302 bucketNs=16 exactBytes=120 bucketBytes=0 exactRetained=31+31 bucketCells=62 afterIdleCleanup=1+1
workload=one-high exactNs=202 bucketNs=11 exactBytes=120 bucketBytes=0 exactRetained=30001+30001 bucketCells=62 afterIdleCleanup=1+1
workload=many-low exactNs=920 bucketNs=13 exactBytes=475 bucketBytes=0 exactRetained=31+31 bucketCells=31031 afterIdleCleanup=63+63
workload=many-high exactNs=990 bucketNs=15 exactBytes=456 bucketBytes=0 exactRetained=30001+30059 bucketCells=31031 afterIdleCleanup=63+63
```

The bucket prototype improved steady dispatch cost, allocation, and fixed-cell
retention. On the regular deterministic stream it stayed within one bucket and
2.90% relative wait error, and retained hot/cold fairness ordering. It nevertheless
failed the mandatory admission rule: a 100-event burst in bucket zero, queried at
30.999 s, had zero exact samples and 100 bucket samples. Exact rejected while the
bucket estimate admitted. Buckets are therefore rejected for production; the exact
algorithm plus bounded cleanup/caps is the smallest design satisfying the declared
contract. `Retry-After` remains configuration-derived and unchanged.

## Verification

- Complete sync-queue module:
  `./gradlew :control-plane-modules:sync-queue:test --rerun-tasks --no-parallel --console=plain --offline`
  succeeded in 9 s (39/39 actionable tasks executed).
- Real HTTP rejection integration:
  `./gradlew :control-plane:test --tests '*SyncQueueBackpressureApiTest' --rerun-tasks --no-parallel --console=plain --offline`
  succeeded in 21 s (78/78 executed), including `Retry-After: 2`.
- JVM package:
  `./gradlew :control-plane:bootJar :control-plane:processAot --no-parallel --console=plain --offline`
  succeeded in 2 s; AOT was intentionally skipped by the non-native profile.
- Native-profile AOT generation and Java compilation:
  `./gradlew :control-plane:processAot :control-plane:compileAotJava :control-plane:nativeCompile -x :control-plane:nativeCompile --no-parallel --console=plain --offline`
  succeeded in 4 s. Native linking was not run.
- Final full repository suite:
  `./gradlew test --rerun-tasks --no-parallel --continue --console=plain --offline`
  succeeded in 4 min 16 s (190/190 actionable tasks executed).

## GitNexus gate

GitNexus 1.6.11 was bound to this `nanofaas` checkout. The index was known stale
and reported four commits behind HEAD. Exact pre-edit upstream impact was MEDIUM
for `WaitEstimator` (9 impacted, 6 direct), LOW for `recordDispatch` (3),
CRITICAL for `estimateWaitSeconds` (8 symbols and seven process families), HIGH
for private `snapshot` (3 symbols and three process families), LOW for `prune`
(6), LOW for `removeFunctionState` (2), and LOW for the public constructor (5).
The four-argument constructor and private fields returned UNKNOWN/zero; exact text
search resolved them to the existing estimator test seam and in-class references.
No absent edge was treated as safety. The CRITICAL/HIGH audit preserved estimator
selection, admission, scheduler dispatch recording, invocation rejection, fairness,
and HTTP `Retry-After` paths and exercised them in focused/integration/full tests.

Final change detection used `--limit 100000`, well above the returned set. The
complete `all` scope returned 8 files and all 15 listed symbols at LOW risk; that
scope correctly included the two unrelated tracked experiment edits. The complete
`staged` scope returned exactly the six P14 files and all 14 listed symbols at LOW
risk. Both reported zero affected processes. The CLI output contained no partial or
truncated marker and the listed-symbol counts matched the declared totals. Given
the four-commit-stale index, the zero-process result is recorded only as a tooling
limitation, never as evidence of safety. `git diff --cached --check` passed.

## Changed files and concerns

- `platform/modules/sync-queue/src/main/java/.../sync/WaitEstimator.java`
- `platform/modules/sync-queue/src/test/java/.../sync/WaitEstimatorRetentionTest.java`
- `docs/experiments/lifecycle-memory-2026-09/P14WaitEstimatorMeasurement.java`
- `docs/experiments/lifecycle-memory-2026-09/P14WaitEstimatorMeasurement.out`
- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- this report

Remaining concerns are explicit: microbenchmark nanoseconds vary by host; native
linking remains later campaign scope; and above 8,192 concurrently represented
functions, additional functions use the global estimate rather than individual
fairness history. State and samples remain hard bounded in that condition.

Next task: P15.

## Fix round 1 — five Important review findings

Fix round 1 starts from P14 commit e0aa2673.

### Owned time-driven cleanup

No scheduler or executor was added. The unconditional SyncScheduler
SmartLifecycle bean remains the sole resource owner. Every scheduler cycle calls
SyncQueueService.maintainEstimator(now) before inspecting the queue. When all
queues are empty, the existing notification wait returns at its bounded 500 ms
safety timeout, so maintenance continues without estimator traffic. stop()
interrupts that wait and synchronously shuts down the same executor.

The integration test uses the real scheduler, queue and estimator. A 100 ms
history remains live on the first empty cycle, is physically removed after the
bounded idle wake, and history recorded after lifecycle stop remains unchanged
for longer than the cadence. It uses condition-based deadlines, not sleep-based
ordering.

Each scheduled cycle has independent non-starving shares: at most 4,096 global
timestamps, plus at most 16 distinct function states with at most 256 timestamps
removed from each. A full global history cannot consume the function share.
Incomplete states rotate to the tail. The per-state AtomicBoolean cleanup marker
admits one queue reference; poll clears it before requeue, preventing duplicate
amplification. Constructors admit at most 8,192 states, and each later state
creation consumes cleanup work before adding at most one candidate, bounding the
rotation by the function-state ceiling.

### Admission-preserving overflow

Before conservative overflow, WaitEstimator checks mapped states, bounded by the
hard 8,192-state ceiling, and conditionally removes the first state whose newest
exact timestamp is older than the inclusive cutoff. The map computation and state
lock recheck prevent eviction of a concurrently refreshed state. Only when no
state is expired and either function-state or total-sample capacity is genuinely
full does an unrepresented function receive infinite wait. Once a state expires,
the next admission check frees it and immediately resumes finite global fallback.

The production queue test drives infinity through SyncQueueAdmissionController
and SyncQueueService.enqueueOrThrow: it yields EST_WAIT with finite configured
Retry-After 7 and performs no arithmetic on infinity. At 8,192/8,193 functions,
measurement gives 30.000000 s for a represented function, infinity for live
overflow, then 0.003663 s after one slot expires. At the 32,768 sample cap,
40,000 exact samples become a conservative 0.009155 s instead of 0.007500 s.
This can reject at an 0.008 s threshold but never creates a false admission;
hot/cold ordering remains unchanged.

### Bucket lifecycle and corrected measurement

The bucket prototype now has an 8,192-function ceiling, unique 16-state rotation,
physical expired-slot cleanup, empty-state retirement and explicit removal.
Assertions require zero bucket function states after idle and removal. Buckets
remain rejected: the boundary fixture still produces exact=0 versus bucket=100
and changes admission.

Every warm-up and repetition creates fresh exact and bucket instances and feeds
each one the same strictly forward-moving timestamps exactly once. Post-idle
maintenance latency/allocation is sampled separately. Assertions enforce count
and relative-error tolerances, regular admission equality, fairness, lifecycle,
8,192/8,193 behavior, recovery and sample-cap conservatism. Approximate zero with
at least ten exact samples is infinite relative error. The hard-coded
retryAfterChanged claim was removed; Retry-After is asserted in production.

Exact measurement command (run as one shell command):

    measure_dir=$(mktemp -d /tmp/nanofaas-p14-measurement.XXXXXX) && javac -cp platform/modules/sync-queue/build/classes/java/main -d "$measure_dir" docs/experiments/lifecycle-memory-2026-09/P14WaitEstimatorMeasurement.java && java -cp "$measure_dir":platform/modules/sync-queue/build/classes/java/main it.unimib.datai.nanofaas.modules.syncqueue.sync.P14WaitEstimatorMeasurement

Recorded medians are in P14WaitEstimatorMeasurement.out. Exact steady dispatch
spans 245–1,125 ns/op in the recorded run; bucket dispatch spans 11–1,256 ns/op.
Exact post-idle maintenance spans 190–32,118 ns/cycle and bucket maintenance
44–1,283 ns/cycle. Allocation and all workloads are in the output; both
representations end at zero retained function state after idle.

### Round-1 RED/GREEN and verification

The focused RED failed compileTestJava on the absent six-argument constructor,
maintain(Instant), and cleanup-candidate diagnostic. Executable measurement then
caught a contradictory range fixture and an undercounted function cleanup-cycle
bound; both were corrected at their fixture/accounting source.

- Full sync-queue module: GREEN, 39/39 executed in 10 s.
- HTTP backpressure/Retry-After integration: GREEN, 78/78 in 23 s.
- JVM package and native-profile AOT generation/Java compilation: GREEN; native
  linking remains outside this task.
- Final repository suite: GREEN, 190/190 in 3 min 44 s.

GitNexus was five commits stale. Pre-edit upstream risk was CRITICAL for
estimateWaitSeconds, HIGH for snapshot, MEDIUM for WaitEstimator and
SyncQueueService, and LOW for resolved scheduler/cleanup paths. UNKNOWN new P14
helpers and measurement symbols were resolved by exact text search. The
CRITICAL/HIGH admission, dispatch, API rejection, fairness and Retry-After paths
were covered by focused, integration and full-suite verification.

Remaining behavior is intentional: while all 8,192 slots are genuinely live, an
unrepresented function can be conservatively rejected; at sample saturation,
high-throughput functions can tie sooner or reject conservatively. Neither case
creates a false admission. Native linking and the minor injected-constructor cap
finding remain deferred.

### Round-1 GitNexus and staging gate

Final detection used limit 100000. Complete all scope returned 12 files, all 14
listed symbols, eight affected scheduler flows and HIGH risk; it correctly
included the two unrelated tracked overload-experiment files. Complete staged
scope returned exactly the ten P14 round-1 files, all 13 listed symbols, the same
eight scheduler flows and HIGH risk. The affected tickOnceInternal flows cover
queue timeout, task, configuration, transition, execution and function data;
sync-queue and full-suite tests exercised them. The stale index attributed the
new nearby service test hunk to timesOutQueuedItem and omitted new helper symbols,
so these results are lower-bound tooling evidence rather than an all-clear.
Neither output had a partial/truncated marker and each listed count matched its
declared total. The staged file list excluded all unrelated files and
git diff --cached --check passed.

## Fix round 2 — reserved first-sample capacity

Fix round 2 starts from P14 fix-round-1 commit `815861c7`. The remaining
Important finding was reproducible: the single 262,144-sample counter could be
exhausted by a few hot functions even while most of the 8,192 function-state
slots were unused. `makeRoomForFunction` then returned false solely because the
sample counter was full, and production admission treated a ninth function as
infinite wait.

### Bounded invariant and implementation

Production now partitions the same 262,144 total per-function sample bound into
8,192 permanently reserved first-sample slots and 253,952 shared extra-sample
permits. The maintained invariant is:

    retainedExtraSamples = sum(max(0, samples(function) - 1)) <= 253952

The first sample of any admitted function state never consumes the shared pool.
Additional samples acquire one permit with a bounded atomic compare-and-set. If
the pool or per-function cap is full, the dispatch replaces that function's
oldest timestamp with the newest one, preserving recency and conservative exact
history without changing counters. No global state scan is added, and each
dispatch performs at most one reservation trim.

Pruning, time-driven cleanup, explicit removal, and expired-state eviction
release the exact difference between `max(0, before - 1)` and
`max(0, after - 1)`. Function creation/removal cannot race with hot histories for
first-sample capacity because those 8,192 slots are never lent to the shared
pool. `makeRoomForFunction` therefore depends only on the function-state ceiling
and existing bounded expired-state recovery. Conservative infinity remains only
for the 8,193rd genuinely live function.

The package-private test seam now exposes function-state, total-sample, cleanup,
and maintenance budgets independently. Constructor validation rejects a total
sample bound smaller than the state bound; injected histories reserve their
extra permits in O(1) per supplied state.

### Round-2 RED/GREEN and verification

The focused RED command was:

    ./gradlew :control-plane-modules:sync-queue:test --tests '*WaitEstimatorRetentionTest' --tests '*SyncQueueServiceTest.liveEstimatorOverflowUsesProductionRejectionWithFiniteRetryAfter' --tests '*SyncQueueServiceTest.sampleSaturationWithUnusedFunctionSlotsRemainsAdmissible' --rerun-tasks --no-parallel --console=plain --offline

It failed exactly two new regressions: few-function saturation retained one state
instead of two, and the real queue rejected a function despite unused state
capacity. The 8,192/8,193 and expiry/removal controls passed, isolating the fault
to sample accounting. The first GREEN attempt exposed legacy fixtures whose
compact constructor encoded equal total/state limits and one fixture whose
maintenance budget reverted to the default; separating those dimensions restored
their intended contracts. The final focused command succeeded with 39/39
actionable tasks.

Distinct regressions now cover few-function sample saturation and creation of a
new history, the production 8,192/8,193 live-state boundary, expiry plus explicit
removal recovery, admission under unused reserved slots, and genuine full-state
rejection with finite configured Retry-After 7.

- Complete sync-queue module: GREEN, 39/39 actionable tasks in 11 s.
- HTTP backpressure integration: GREEN, 78/78 actionable tasks in 22 s.
- Full repository suite after the shared admission change: GREEN, 190/190
  actionable tasks in 4 min.

### Round-2 impact audit

The GitNexus 1.6.11 index was six commits behind HEAD. Exact upstream impact was
MEDIUM for `WaitEstimator` (nine impacted, six direct), LOW for
`recordDispatch` (three impacted), CRITICAL for `estimateWaitSeconds` (eight
impacted across seven process families), HIGH for its per-function `snapshot`
(three impacted across three process families), and LOW for exact
`removeFunctionState` (two impacted). The round-1 private helpers,
`runBoundedCleanup`, `evictExpiredFunctionState`, `makeRoomForFunction`, and the
expanded constructor were absent/UNKNOWN in the stale index. Exact text search
resolved them to `WaitEstimator`; constructor call sites additionally span
sync-queue configuration/tests and the queue benchmark. No absent or zero result
was treated as safety. The CRITICAL/HIGH admission paths were audited with the
real queue regression, HTTP integration, and full suite.

### Round-2 GitNexus and staging gate

Complete change detection used `--limit 100000`. All scope returned six files,
all ten listed symbols, zero affected processes, and LOW risk; it included the
two unrelated tracked overload-experiment files. Staged scope returned exactly
the four P14 files, all nine listed symbols, zero affected processes, and LOW
risk. Neither output reported partial/truncated results, and each listed-symbol
count matched its declared total.

The stale index omitted the new retention test symbols and new private permit
helpers, and attributed the nearby service-test hunk to
`aQueueWaitTimeoutStillRecordsTheInvocationsEndToEndConclusion`; it also reported
unchanged nearby `prune`, `samples`, `throughput`, and `ThroughputSnapshot`
symbols. These are recorded as six-commit-stale lower-bound tooling results, not
an all-clear. Exact pre-edit CRITICAL/HIGH impact and text-search resolution
remain the authoritative audit for production admission paths.
