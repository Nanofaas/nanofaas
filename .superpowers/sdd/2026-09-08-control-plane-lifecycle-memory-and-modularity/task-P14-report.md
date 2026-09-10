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
