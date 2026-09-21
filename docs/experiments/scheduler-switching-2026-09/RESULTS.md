# Scheduler switching — results (Task 12c)

Campaign: `docs/experiments/scheduler-switching-2026-09/`, issue #208 manual scheduler
switching. This file records what was measured, against which frozen thresholds, and how each
figure can be re-derived. Nothing here is asserted from memory: every number traces to a line in
`raw/` and to the command that produced the file.

## What was measured, and by what

`SchedulerSwitchBenchmark.java` drives the **real** `SchedulerEngine` with the **two real
preserved strategies** (`PerFunctionSchedulingStrategy`, `SharedQueueSchedulingStrategy`) against a
real `FunctionCapacityRegistry` and a real `PendingWorkStore`. No production code is involved and
none was changed: the harness is standalone, compiled against the classpath
`:execution-runtime:printTestClasspath` prints, and is not a test.

Four decisions in the harness are what make the numbers mean anything, and each is the spec's own
requirement rather than a preference:

- **A real clock.** The subject is a pause and a latency distribution; a simulated clock returns
  whatever the harness advanced it by. The engine's own `nanoTime` supplier is bound to
  `System.nanoTime`, so the engine and the harness read one clock. The budget comparison uses the
  engine's own `SwitchObserver` figure — the duration the engine itself records around a switch.
  The client-side elapsed time is emitted beside it and is never substituted for it.
- **Service duration is modelled.** `ControlledDispatch.submit` publishes and returns, as
  `EngineDispatch` documents, but the capacity lease is held for the function's declared service
  time and released only once that time has actually elapsed. A dispatcher that completes
  instantly frees every slot in the pass that took it, which makes both strategies look identical
  and hides queueing — spec §11: *«il primo può nascondere il costo che interessa»*.
- **Useful throughput, not dispatch rate.** Every ticket carries a queue deadline at
  `admission + contractMillis` and every completion is classified against the same deadline. Work
  the engine expires before dispatch is counted as a lost caller. Spec §11: *«una strategia può
  avviare la stessa quantità di lavoro e far scadere più chiamanti»*.
- **One dispatch path, no shadow.** Selection, ordering and admission are the engine's; the
  harness implements none of them. `ControlledDispatch` occupies the seat the real transport
  adapter occupies on the far side of `EngineDispatch` rather than sitting beside it, and the
  index accounting wraps the strategy the engine actually uses instead of maintaining a parallel
  index.

Two independent clocks are **not** used anywhere. The simulated/degenerate configurations the spec
warns about — instant completion, dispatch-rate-only accounting — are not available as options:
they are what the harness was built to avoid.

## The comparison key, stated before the numbers

`budgets.json` records thresholds, not results; Task 0 measured no performance figures. The only
baseline this campaign can compare against is therefore one it measures itself:

- **Isolated scheduler comparison** (spec §11, level 1): same engine, same admission, same
  contract, same readiness, same service model, same corpus and same offered rate — only the
  strategy, and whether a switch happens, vary.
- **The four percentage budgets** are read as *the switched arm against the target strategy's
  no-change arm on the same workload*: does performing the manual switch regress steady-state p99,
  useful throughput, CPU per useful completion, or post-GC heap? The switch lands at the start of
  the measured window — the load is running, the driver is servicing it, the backlog the switch has
  to rebuild is a real one, and the window opens only once the switch has committed — so the whole
  measured window runs on the target strategy and the delta against that strategy's own no-change
  arm is the switch's own residue. A negative delta (an improvement) is not a budget miss. The
  per-workload table also carries each strategy's no-change arm side by side, so a
  **strategy-to-strategy** difference is visible and is reported as a measurement, not as a winner:
  spec §11 — *«risultati simili o un vantaggio dell'altra strategia non autorizzano a eliminarla»*.
- **A delta over budget is not automatically an effect.** Each arm's five repetitions are reported
  as a range; arms whose ranges overlap are not separated by this measurement, and the brief's own
  instruction for that case is to declare the result not distinguishable rather than to read the
  delta as a regression. The regression table carries both facts: the delta against the budget, and
  whether the arms' ranges are disjoint.
- **Corpus and offered rate are identical across arms.** The arrival process is seeded from
  `(workload, repetition)`, so the four arms of one repetition see the same arrivals in the same
  order; only the arm differs.
- **Repetitions alternate** (the arm order is reversed on even repetitions) and every arm gets an
  identical warm-up run before the first measured repetition. Five repetitions per arm, as
  `budgets.json` requires.

## Reproducing it

```bash
cd docs/experiments/scheduler-switching-2026-09
./run.sh --label=full          # the campaign: every part, budgets.json's own values
python3 summarize.py raw/full.jsonl   # the tables below
```

`run.sh` resolves the classpath once through `:execution-runtime:printTestClasspath` (Gradle task
added by this task, matching the three that already exist in sibling modules), compiles the
harness, stops Gradle's daemon, and runs the JVM with a fixed pre-touched 1 GiB heap so the arms
share one heap. The harness reads `budgets.json` itself rather than being told the thresholds, and
echoes what it read into the `header` line of its output — so a result cannot be compared against
thresholds that differ from the frozen file.

The shorter runs used while the harness was being brought up, and their single-value overrides:

```bash
./run.sh --label=smoke --parts=backlog,profiles --backlogs=100 --repetitions=1 \
    --profiles=low-load --window-ms=1500
```

A full campaign run is a foreground measurement: no Gradle build, no other sampling run, nothing
else CPU-bound. `run.sh` stops the Gradle daemon before the measured JVM starts for that reason —
for a latency, CPU or post-GC heap figure a concurrent run does not corrupt a file, it corrupts
the number. `raw/host-quietness.txt` records what was checked, the load average observed, and the
part of the campaign the load sampling does **not** cover. In the artifact itself, the
useful-throughput spread across the five repetitions has a median of 3.12 % over the 48
(workload, arm) pairs, which is the shape of an undisturbed host.

## The headline: the frozen budgets, one row each

Generated by `python3 summarize.py raw/full.jsonl`; the source column names the figure in the artifact each observed value was read from.

| budget (budgets.json) | frozen | observed | source | verdict |
|---|---|---|---|---|
| maxSwitchPauseMs | 250 ms | 3.984 ms | max engine pause over every measured switch (sweep + workloads + 1000-switch run) (summary.pauseMaxMs) | PASS |
| maxSwitchPauseP99Ms | 100 ms | 0.266 ms | p99 over the 1000-switch run (summary.soakPauseP99Ms) | PASS |
| maxSwitchPreparationMs | 2000 ms | 3.984 ms | total switch duration, an upper bound on the preparation phase (summary.pauseMaxMs) | PASS |
| maxLiveStrategyIndexes | 2  | 2 | max live indexes observed (samples, switch events, baseline) | PASS |
| switchesInSoak | 1000  | 1000 committed / 0 refused | baseline line | PASS |
| maxSteadyP99RegressionPercent | 5 % | +76.13 % | worst switched-vs-no-change delta (see the regression table); 4 of 24 comparisons over budget, 1 of those distinguishable | MISS |
| maxUsefulThroughputRegressionPercent | 5 % | +4.11 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxCpuPerCompletionRegressionPercent | 10 % | +23.83 % | worst switched-vs-no-change delta (see the regression table); 8 of 24 comparisons over budget, 0 of those distinguishable | MISS |
| maxPostGcHeapRegressionPercent | 10 % | +0.01 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| repetitions | 5  | [5] measured samples per (workload, arm) | counted over every workload sample in the artifact | PASS |
| switchBacklogSizes | [0, 100, 1000, 10000]  | [0, 100, 1000, 10000] | sizes swept, each with 0/5 reps, 100/5 reps, 1000/5 reps, 10000/5 reps | PASS |

Client-side corroboration, never substituted for the engine's figure: worst client elapsed 4.299 ms (budgets give no client figure).

## What the numbers say

**The gate figure passes, by roughly sixty times.** The engine's own pause over 1 140 measured
manual switches — 20 in the backlog sweep, 120 across the twelve workloads, 1 000 in the
return-to-baseline phase — has a maximum of **3.984 ms** against a frozen `maxSwitchPauseMs` of
250 ms. Over the 1 000-switch phase alone the p99 is **0.266 ms** against a frozen
`maxSwitchPauseP99Ms` of 100 ms. The pause grows with the backlog the rebuild has to carry
(0.13 ms p50 at an empty queue, 1.91 ms p50 at 10 000 pending) and stays two orders of magnitude
inside the budget at the largest size `budgets.json` names. Every switch in the campaign committed;
none was refused.

The structural budgets pass exactly: `maxLiveStrategyIndexes` is 2 (one active, one candidate
during a rebuild — observed directly on the strategy the engine uses, never 3), and the 1 000-switch
phase performed exactly `switchesInSoak` switches over 1 001 indexes created and 1 000 cleared, so
the engine returns to one live index.

**The return-to-baseline check passes.** After 1 000 switches, against a no-switch control phase
driven over the same wall clock, corpus and offered rate: pending 399 against 400 (−0.25 %), the
largest per-function reservation difference is one ticket, live indexes 1 in both phases, and the
work accounting is exact in both phases. Throughput and heap are within 0.7 % of the control.

**Work conservation is exact in all 240 measured runs** — admitted equals completed + expired +
removed + rejected + pending + claimed + submitting + in flight, with zero driver failures and no
sample cap reached. Since a single duplicated completion breaks that identity, this is also the
check that no work was duplicated across a switch; and it holding across 120 switches under load is
the continuity result the spec asks for.

**Three of the four regression budgets pass; one does not, on one workload.**

| budget | frozen | worst observed | verdict |
|---|---|---|---|
| `maxUsefulThroughputRegressionPercent` | 5 % | +4.11 % | PASS |
| `maxPostGcHeapRegressionPercent` | 10 % | +0.01 % | PASS |
| `maxSteadyP99RegressionPercent` | 5 % | +76.13 % | **MISS** — 4 of 24 comparisons over budget, 1 of those distinguishable |
| `maxCpuPerCompletionRegressionPercent` | 10 % | +23.83 % | **MISS** — 8 of 24 comparisons over budget, 0 of those distinguishable |

The CPU misses are all inside the arms' own ranges (`overlaps` in the table below). `processCpuTime`
is quantised to 10 ms on Linux, so on workloads completing hundreds of items per window the figure
moves in 10 ms steps; none of the eight is separable from run-to-run dispersion by this
measurement, and none is reported as an effect. The per-workload table carries `alloc/useful`, which
is not quantised, next to it.

### The one distinguishable miss, and what it is

`capacity-change`, switching `shared-queue → per-function`: p99 **48.704 ms → 85.784 ms
(+76.13 %)**, with disjoint five-repetition ranges (35.7–53.8 ms against 63.8–97.2 ms). This is a
real separation at the protocol's window and is reported as a miss against the frozen 5 %.

It is **not** the switch mechanism. The evidence is in the artifact, and it points at the queue the
previous strategy left behind:

- the arm enters the measured window with `maxBacklog` 82–99 against 26–33 for `per-function
  (no change)`, and `pendingAtSwitch` 54–72 — the shared-queue phase runs a deeper standing queue,
  and the switch does not reset it;
- the whole distribution is shifted, not just the tail: p50 7.1–17.2 ms against 6.2–8.1 ms, p95
  38.8–64.1 ms against 18.9–27.1 ms. A pause would not do that; a deeper standing queue would;
- the difference decays to nothing as the inherited queue drains. Same workload, same arm pair,
  three repetitions each, only the window length changing:

| window | `per-function (no change)` p99 (range) | `shared-queue → per-function` p99 (range) | delta |
|---|---|---|---|
| 2 500 ms (the protocol) | 48.704 (35.726–53.799) | 85.784 (63.788–97.171) | **+76.13 %** |
| 4 000 ms | 37.588 (34.532–63.348) | 85.045 (84.215–93.777) | +126.3 % |
| 10 000 ms | 58.145 (49.770–63.386) | 58.475 (53.553–59.416) | **+0.6 %**, ranges overlap |

  (`raw/diagnostic-cc-window-4000.jsonl`, `raw/diagnostic-cc-window-10000.jsonl`.)

The same shape appears on the other workload where the tail moved — `head-of-line-blocking`,
`shared-queue → per-function`, +12.69 % at 2 500 ms with overlapping ranges, +8.0 % at 4 s, and
**+3.9 % at 10 s with overlapping ranges and an unchanged p50** (`raw/diagnostic-hol-window-*.jsonl`).

**What this does and does not establish.** A caller that pushes traffic immediately after a manual
switch, on these workloads, does see a higher tail for a few seconds while the inherited backlog
drains; that is reproducible and the ranges are disjoint. Whether that is a violation of a budget
named *maxSteady**P99**Regression* is a question about what the frozen word "steady" covers, and it
belongs to the reviewer — this task does not get to answer it by re-picking the window after seeing
the result. **No threshold was changed**, nothing was adjusted a posteriori, and the protocol above
is the one the campaign ran with. The measurement that would separate the two explanations
unambiguously is a protocol that drains the queue between the switch and the window; it is not run
here, and the confound is stated rather than argued away.

### No winner is declared

The per-workload table shows the two strategies' no-change arms side by side, and they differ
substantially on some workloads — `head-of-line-blocking` p99 2.919 ms against 136.160 ms,
`capacity-change` 48.704 ms against 145.629 ms, `saturated` useful throughput 635.3/s against
717.9/s, `heterogeneous-burst` cpu/useful 37.91 µs against 61.37 µs, and on `switch-under-load` the
two are within 4 % of each other. Those are measurements of two preserved implementations on
one corpus, not a ranking: spec §11 — *«Entrambe le strategie esistenti restano supportate… Non
servono a cercare uno scheduler dominante, eliminare una delle due strategie o attivare una
selezione automatica»*, and *«risultati simili o un vantaggio dell'altra strategia non autorizzano
a eliminarla»*. Nothing in this document should be read as recommending the removal of either.

## Every table

## Pause sweep (budgets.json switchBacklogSizes)

| requested backlog | switches | pending at switch | pause p50 ms | pause max ms | client max ms | live indexes max | outcome |
|---|---|---|---|---|---|---|---|
| 0 | 5 | 0-0 | 0.130 | 3.984 | 4.299 | 1 | COMMITTED |
| 100 | 5 | 99-99 | 0.272 | 0.537 | 0.549 | 1 | COMMITTED |
| 1000 | 5 | 999-999 | 0.583 | 0.713 | 0.733 | 1 | COMMITTED |
| 10000 | 5 | 9999-10000 | 1.909 | 2.318 | 2.339 | 1 | COMMITTED |

## Per-workload medians over the 5 repetitions (min-max in brackets)

| workload | arm | reps | useful/s | p99 ms | cpu/useful us | alloc/useful B | post-GC heap MB | pending | conserved |
|---|---|---|---|---|---|---|---|---|---|
| capacity-change | per-function (no change) | 5 | 1791.0 (1766.4-1810.4) | 48.704 | 26.80 | 884 | 27.73 | 3 | True |
| capacity-change | per-function -> shared-queue | 5 | 1683.8 (1676.0-1724.4) | 145.380 | 30.88 | 929 | 27.76 | 64 | True |
| capacity-change | shared-queue (no change) | 5 | 1617.4 (1616.0-1654.1) | 145.629 | 36.34 | 961 | 27.76 | 63 | True |
| capacity-change | shared-queue -> per-function | 5 | 1743.1 (1718.8-1777.9) | 85.784 | 29.82 | 876 | 27.74 | 7 | True |
| churn-drain | per-function (no change) | 5 | 1186.8 (1157.0-1194.5) | 2.519 | 30.49 | 2603 | 27.74 | 0 | True |
| churn-drain | per-function -> shared-queue | 5 | 1188.4 (1158.2-1194.3) | 2.589 | 37.98 | 2564 | 27.74 | 0 | True |
| churn-drain | shared-queue (no change) | 5 | 1190.2 (1157.3-1191.3) | 2.579 | 36.95 | 2531 | 27.74 | 0 | True |
| churn-drain | shared-queue -> per-function | 5 | 1187.6 (1157.6-1192.5) | 2.591 | 30.30 | 2632 | 27.74 | 0 | True |
| head-of-line-blocking | per-function (no change) | 5 | 181.6 (168.8-195.5) | 2.919 | 198.24 | 2764 | 27.80 | 125 | True |
| head-of-line-blocking | per-function -> shared-queue | 5 | 174.8 (162.3-191.5) | 124.704 | 228.83 | 2900 | 27.80 | 135 | True |
| head-of-line-blocking | shared-queue (no change) | 5 | 174.8 (162.0-189.9) | 136.160 | 196.08 | 2918 | 27.81 | 145 | True |
| head-of-line-blocking | shared-queue -> per-function | 5 | 180.7 (168.8-195.1) | 3.289 | 221.24 | 2788 | 27.80 | 126 | True |
| heterogeneous-burst | per-function (no change) | 5 | 1062.4 (1050.3-1078.7) | 297.943 | 37.91 | 1118 | 27.77 | 0 | True |
| heterogeneous-burst | per-function -> shared-queue | 5 | 984.0 (971.5-1003.7) | 297.743 | 45.25 | 1236 | 27.77 | 0 | True |
| heterogeneous-burst | shared-queue (no change) | 5 | 977.5 (947.9-982.7) | 298.350 | 61.37 | 1371 | 27.77 | 0 | True |
| heterogeneous-burst | shared-queue -> per-function | 5 | 1053.6 (1035.1-1091.5) | 299.355 | 45.56 | 1118 | 27.77 | 0 | True |
| hot-plus-50-sporadic | per-function (no change) | 5 | 2365.1 (2331.7-2397.8) | 7.500 | 28.96 | 1010 | 27.75 | 0 | True |
| hot-plus-50-sporadic | per-function -> shared-queue | 5 | 2365.3 (2330.1-2399.2) | 5.744 | 28.34 | 960 | 27.75 | 0 | True |
| hot-plus-50-sporadic | shared-queue (no change) | 5 | 2372.7 (2327.8-2398.0) | 8.066 | 30.65 | 998 | 27.75 | 0 | True |
| hot-plus-50-sporadic | shared-queue -> per-function | 5 | 2366.7 (2333.4-2399.5) | 6.943 | 27.04 | 1008 | 27.75 | 0 | True |
| hot-plus-500-sporadic | per-function (no change) | 5 | 3543.2 (3508.7-3592.7) | 5.490 | 28.04 | 1099 | 27.98 | 0 | True |
| hot-plus-500-sporadic | per-function -> shared-queue | 5 | 3554.0 (3520.2-3602.2) | 5.237 | 24.61 | 1102 | 27.98 | 0 | True |
| hot-plus-500-sporadic | shared-queue (no change) | 5 | 3551.4 (3521.4-3590.1) | 5.557 | 27.26 | 1077 | 27.98 | 0 | True |
| hot-plus-500-sporadic | shared-queue -> per-function | 5 | 3561.0 (3516.7-3590.8) | 5.881 | 28.96 | 1142 | 27.98 | 1 | True |
| low-load | per-function (no change) | 5 | 33.6 (28.4-39.2) | 2.785 | 481.93 | 682 | 27.72 | 0 | True |
| low-load | per-function -> shared-queue | 5 | 33.6 (28.4-39.2) | 2.797 | 481.93 | 549 | 27.72 | 0 | True |
| low-load | shared-queue (no change) | 5 | 33.6 (28.4-39.2) | 2.803 | 510.20 | 619 | 27.72 | 0 | True |
| low-load | shared-queue -> per-function | 5 | 33.6 (28.4-39.2) | 2.748 | 563.38 | 620 | 27.72 | 0 | True |
| mixed-kind-retry | per-function (no change) | 5 | 1517.5 (1491.9-1531.8) | 4.931 | 34.57 | 903 | 27.73 | 0 | True |
| mixed-kind-retry | per-function -> shared-queue | 5 | 1523.6 (1498.3-1533.0) | 4.864 | 31.49 | 852 | 27.73 | 0 | True |
| mixed-kind-retry | shared-queue (no change) | 5 | 1526.5 (1496.8-1530.1) | 4.849 | 28.81 | 851 | 27.73 | 0 | True |
| mixed-kind-retry | shared-queue -> per-function | 5 | 1525.9 (1506.2-1540.1) | 4.970 | 26.55 | 903 | 27.73 | 0 | True |
| queued | per-function (no change) | 5 | 460.2 (455.2-478.0) | 78.889 | 69.87 | 829 | 27.66 | 2 | True |
| queued | per-function -> shared-queue | 5 | 467.4 (456.2-478.6) | 59.203 | 70.11 | 843 | 27.66 | 3 | True |
| queued | shared-queue (no change) | 5 | 459.6 (456.1-481.5) | 53.246 | 78.88 | 838 | 27.66 | 2 | True |
| queued | shared-queue -> per-function | 5 | 456.3 (451.0-480.6) | 82.678 | 78.95 | 822 | 27.66 | 2 | True |
| saturated | per-function (no change) | 5 | 635.3 (630.3-653.8) | 61.947 | 126.90 | 2603 | 27.78 | 107 | True |
| saturated | per-function -> shared-queue | 5 | 679.1 (672.1-693.1) | 61.472 | 100.41 | 2607 | 27.77 | 98 | True |
| saturated | shared-queue (no change) | 5 | 717.9 (698.0-724.4) | 61.369 | 83.56 | 2469 | 27.77 | 95 | True |
| saturated | shared-queue -> per-function | 5 | 653.2 (644.3-657.2) | 61.991 | 99.32 | 2568 | 27.77 | 100 | True |
| switch-under-load | per-function (no change) | 5 | 978.2 (968.3-980.5) | 253.105 | 61.15 | 1518 | 27.86 | 187 | True |
| switch-under-load | per-function -> shared-queue | 5 | 949.0 (928.8-966.0) | 251.231 | 62.89 | 1603 | 27.86 | 168 | True |
| switch-under-load | shared-queue (no change) | 5 | 940.3 (935.4-961.9) | 250.866 | 66.53 | 1643 | 27.86 | 169 | True |
| switch-under-load | shared-queue -> per-function | 5 | 993.8 (982.3-1007.0) | 251.820 | 48.27 | 1475 | 27.85 | 165 | True |
| unqueued | per-function (no change) | 5 | 466.4 (451.4-470.7) | 4.086 | 68.61 | 770 | 27.66 | 0 | True |
| unqueued | per-function -> shared-queue | 5 | 466.7 (451.0-471.5) | 3.932 | 68.55 | 712 | 27.66 | 0 | True |
| unqueued | shared-queue (no change) | 5 | 467.4 (451.5-471.0) | 4.088 | 70.86 | 732 | 27.66 | 0 | True |
| unqueued | shared-queue -> per-function | 5 | 467.6 (451.9-470.6) | 4.214 | 84.96 | 779 | 27.66 | 0 | True |

## Workload coverage table notes

The per-workload table's `useful/s` is the useful throughput — completions inside the contract
deadline, per second of measured window. `p99` is the end-to-end latency (admission to
completion) at the 99th percentile over the window, for samples admitted inside the window.
`cpu/useful` is process CPU time divided by useful completions. `alloc/useful` is total allocated
bytes (all threads, harness included) divided by useful completions. `post-GC heap` is the live
heap after three forced collections. `pending` is the queue population at the moment the worker
was stopped. `conserved` is the accounting identity below.

## Switched arm vs the same strategy's no-change arm (median of 5 repetitions)

A delta over budget is a miss. `dispersion` says whether it is *distinguishable*: arms whose 5-repetition ranges overlap are not separated by this measurement, and the brief's own criterion for that case is to declare the result not distinguishable rather than to read the delta as an effect.

| workload | switch | metric | no-change (5-rep range) | switched (5-rep range) | delta % | budget % | verdict | dispersion |
|---|---|---|---|---|---|---|---|---|
| capacity-change | per-function -> shared-queue | steady p99 | 145.629 (142.771-150.248) | 145.380 (144.728-148.275) | -0.17 | 5 | PASS | overlaps |
| capacity-change | per-function -> shared-queue | useful throughput | 1617.410 (1615.994-1654.083) | 1683.821 (1675.983-1724.432) | +4.11 | 5 | PASS | disjoint |
| capacity-change | per-function -> shared-queue | cpu per useful completion | 36337.000 (29013.000-47029.000) | 30878.000 (25510.000-45152.000) | -15.02 | 10 | PASS | overlaps |
| capacity-change | per-function -> shared-queue | post-GC heap | 27762872.000 (27760384.000-27775200.000) | 27763696.000 (27760568.000-27769464.000) | +0.00 | 10 | PASS | overlaps |
| capacity-change | shared-queue -> per-function | steady p99 | 48.704 (35.726-53.799) | 85.784 (63.788-97.171) | +76.13 | 5 | MISS | disjoint |
| capacity-change | shared-queue -> per-function | useful throughput | 1791.049 (1766.445-1810.360) | 1743.059 (1718.804-1777.886) | -2.68 | 5 | PASS | overlaps |
| capacity-change | shared-queue -> per-function | cpu per useful completion | 26797.000 (24780.000-30925.000) | 29823.000 (25235.000-48859.000) | +11.29 | 10 | MISS | overlaps |
| capacity-change | shared-queue -> per-function | post-GC heap | 27731944.000 (27730352.000-27736688.000) | 27736080.000 (27731960.000-27740072.000) | +0.01 | 10 | PASS | overlaps |
| churn-drain | per-function -> shared-queue | steady p99 | 2.579 (2.419-2.812) | 2.589 (2.494-2.772) | +0.39 | 5 | PASS | overlaps |
| churn-drain | per-function -> shared-queue | useful throughput | 1190.230 (1157.263-1191.269) | 1188.378 (1158.164-1194.301) | -0.16 | 5 | PASS | overlaps |
| churn-drain | per-function -> shared-queue | cpu per useful completion | 36949.000 (31098.000-47027.000) | 37983.000 (30292.000-47027.000) | +2.80 | 10 | PASS | overlaps |
| churn-drain | per-function -> shared-queue | post-GC heap | 27738408.000 (27738312.000-27738560.000) | 27738640.000 (27738472.000-27739056.000) | +0.00 | 10 | PASS | overlaps |
| churn-drain | shared-queue -> per-function | steady p99 | 2.519 (2.422-2.847) | 2.591 (2.533-2.634) | +2.88 | 5 | PASS | overlaps |
| churn-drain | shared-queue -> per-function | useful throughput | 1186.756 (1157.004-1194.486) | 1187.633 (1157.584-1192.467) | +0.07 | 5 | PASS | overlaps |
| churn-drain | shared-queue -> per-function | cpu per useful completion | 30487.000 (26945.000-43756.000) | 30303.000 (24187.000-40241.000) | -0.60 | 10 | PASS | overlaps |
| churn-drain | shared-queue -> per-function | post-GC heap | 27738552.000 (27738432.000-27738656.000) | 27738688.000 (27738616.000-27738808.000) | +0.00 | 10 | PASS | overlaps |
| head-of-line-blocking | per-function -> shared-queue | steady p99 | 136.160 (124.755-170.889) | 124.704 (118.852-151.629) | -8.41 | 5 | PASS | overlaps |
| head-of-line-blocking | per-function -> shared-queue | useful throughput | 174.793 (161.980-189.864) | 174.795 (162.331-191.526) | +0.00 | 5 | PASS | overlaps |
| head-of-line-blocking | per-function -> shared-queue | cpu per useful completion | 196078.000 (189473.000-246913.000) | 228832.000 (216867.000-292275.000) | +16.70 | 10 | MISS | overlaps |
| head-of-line-blocking | per-function -> shared-queue | post-GC heap | 27805216.000 (27792984.000-27808984.000) | 27801656.000 (27791544.000-27804880.000) | -0.01 | 10 | PASS | overlaps |
| head-of-line-blocking | shared-queue -> per-function | steady p99 | 2.919 (2.615-3.115) | 3.289 (2.846-3.531) | +12.69 | 5 | MISS | overlaps |
| head-of-line-blocking | shared-queue -> per-function | useful throughput | 181.598 (168.779-195.516) | 180.655 (168.763-195.146) | -0.52 | 5 | PASS | overlaps |
| head-of-line-blocking | shared-queue -> per-function | cpu per useful completion | 198237.000 (168776.000-260663.000) | 221238.000 (189473.000-327868.000) | +11.60 | 10 | MISS | overlaps |
| head-of-line-blocking | shared-queue -> per-function | post-GC heap | 27796496.000 (27789680.000-27799512.000) | 27796640.000 (27790832.000-27798000.000) | +0.00 | 10 | PASS | overlaps |
| heterogeneous-burst | per-function -> shared-queue | steady p99 | 298.350 (294.952-301.135) | 297.743 (295.690-300.735) | -0.20 | 5 | PASS | overlaps |
| heterogeneous-burst | per-function -> shared-queue | useful throughput | 977.516 (947.859-982.731) | 983.989 (971.540-1003.744) | +0.66 | 5 | PASS | overlaps |
| heterogeneous-burst | per-function -> shared-queue | cpu per useful completion | 61374.000 (40749.000-63291.000) | 45248.000 (35856.000-52588.000) | -26.27 | 10 | PASS | overlaps |
| heterogeneous-burst | per-function -> shared-queue | post-GC heap | 27774288.000 (27773664.000-27774384.000) | 27774448.000 (27773944.000-27774544.000) | +0.00 | 10 | PASS | overlaps |
| heterogeneous-burst | shared-queue -> per-function | steady p99 | 297.943 (296.763-299.434) | 299.355 (296.916-300.292) | +0.47 | 5 | PASS | overlaps |
| heterogeneous-burst | shared-queue -> per-function | useful throughput | 1062.444 (1050.323-1078.658) | 1053.563 (1035.114-1091.476) | -0.84 | 5 | PASS | overlaps |
| heterogeneous-burst | shared-queue -> per-function | cpu per useful completion | 37907.000 (33532.000-44493.000) | 45558.000 (33809.000-57937.000) | +20.18 | 10 | MISS | overlaps |
| heterogeneous-burst | shared-queue -> per-function | post-GC heap | 27767288.000 (27766576.000-27767616.000) | 27767096.000 (27766640.000-27767440.000) | -0.00 | 10 | PASS | overlaps |
| hot-plus-50-sporadic | per-function -> shared-queue | steady p99 | 8.066 (4.432-8.673) | 5.744 (5.138-9.681) | -28.79 | 5 | PASS | overlaps |
| hot-plus-50-sporadic | per-function -> shared-queue | useful throughput | 2372.699 (2327.829-2398.042) | 2365.261 (2330.115-2399.160) | -0.31 | 5 | PASS | overlaps |
| hot-plus-50-sporadic | per-function -> shared-queue | cpu per useful completion | 30653.000 (20229.000-38358.000) | 28338.000 (25544.000-37199.000) | -7.55 | 10 | PASS | overlaps |
| hot-plus-50-sporadic | per-function -> shared-queue | post-GC heap | 27750016.000 (27749536.000-27752328.000) | 27750344.000 (27750104.000-27751144.000) | +0.00 | 10 | PASS | overlaps |
| hot-plus-50-sporadic | shared-queue -> per-function | steady p99 | 7.500 (5.916-8.067) | 6.943 (5.695-7.219) | -7.43 | 5 | PASS | overlaps |
| hot-plus-50-sporadic | shared-queue -> per-function | useful throughput | 2365.101 (2331.654-2397.799) | 2366.708 (2333.409-2399.458) | +0.07 | 5 | PASS | overlaps |
| hot-plus-50-sporadic | shared-queue -> per-function | cpu per useful completion | 28965.000 (21985.000-30025.000) | 27040.000 (25244.000-28946.000) | -6.65 | 10 | PASS | overlaps |
| hot-plus-50-sporadic | shared-queue -> per-function | post-GC heap | 27749976.000 (27745288.000-27752024.000) | 27750696.000 (27750152.000-27751528.000) | +0.00 | 10 | PASS | overlaps |
| hot-plus-500-sporadic | per-function -> shared-queue | steady p99 | 5.557 (4.173-6.485) | 5.237 (4.624-7.118) | -5.76 | 5 | PASS | overlaps |
| hot-plus-500-sporadic | per-function -> shared-queue | useful throughput | 3551.387 (3521.446-3590.081) | 3553.961 (3520.168-3602.192) | +0.07 | 5 | PASS | overlaps |
| hot-plus-500-sporadic | per-function -> shared-queue | cpu per useful completion | 27257.000 (22279.000-29223.000) | 24614.000 (22509.000-28232.000) | -9.70 | 10 | PASS | overlaps |
| hot-plus-500-sporadic | per-function -> shared-queue | post-GC heap | 27976944.000 (27975384.000-27978720.000) | 27977424.000 (27975528.000-27979024.000) | +0.00 | 10 | PASS | overlaps |
| hot-plus-500-sporadic | shared-queue -> per-function | steady p99 | 5.490 (4.130-6.205) | 5.881 (4.567-7.589) | +7.13 | 5 | MISS | overlaps |
| hot-plus-500-sporadic | shared-queue -> per-function | useful throughput | 3543.174 (3508.716-3592.690) | 3561.034 (3516.681-3590.797) | +0.50 | 5 | PASS | overlaps |
| hot-plus-500-sporadic | shared-queue -> per-function | cpu per useful completion | 28036.000 (22578.000-30779.000) | 28956.000 (19094.000-31847.000) | +3.28 | 10 | PASS | overlaps |
| hot-plus-500-sporadic | shared-queue -> per-function | post-GC heap | 27976680.000 (27976176.000-27978176.000) | 27977272.000 (27976336.000-27977608.000) | +0.00 | 10 | PASS | overlaps |
| low-load | per-function -> shared-queue | steady p99 | 2.803 (2.761-2.896) | 2.797 (2.544-2.810) | -0.22 | 5 | PASS | overlaps |
| low-load | per-function -> shared-queue | useful throughput | 33.581 (28.380-39.171) | 33.588 (28.396-39.192) | +0.02 | 5 | PASS | overlaps |
| low-load | per-function -> shared-queue | cpu per useful completion | 510204.000 (465116.000-833333.000) | 481927.000 (408163.000-697674.000) | -5.54 | 10 | PASS | overlaps |
| low-load | per-function -> shared-queue | post-GC heap | 27718368.000 (27717472.000-27718464.000) | 27718472.000 (27717888.000-27718576.000) | +0.00 | 10 | PASS | overlaps |
| low-load | shared-queue -> per-function | steady p99 | 2.785 (2.539-2.982) | 2.748 (2.578-3.002) | -1.33 | 5 | PASS | overlaps |
| low-load | shared-queue -> per-function | useful throughput | 33.577 (28.397-39.169) | 33.581 (28.389-39.182) | +0.01 | 5 | PASS | overlaps |
| low-load | shared-queue -> per-function | cpu per useful completion | 481927.000 (408163.000-595238.000) | 563380.000 (481927.000-697674.000) | +16.90 | 10 | MISS | overlaps |
| low-load | shared-queue -> per-function | post-GC heap | 27718464.000 (27705384.000-27718560.000) | 27718616.000 (27718472.000-27718688.000) | +0.00 | 10 | PASS | overlaps |
| mixed-kind-retry | per-function -> shared-queue | steady p99 | 4.849 (4.501-5.380) | 4.864 (4.592-4.925) | +0.32 | 5 | PASS | overlaps |
| mixed-kind-retry | per-function -> shared-queue | useful throughput | 1526.459 (1496.768-1530.147) | 1523.577 (1498.254-1533.016) | -0.19 | 5 | PASS | overlaps |
| mixed-kind-retry | per-function -> shared-queue | cpu per useful completion | 28810.000 (28750.000-37413.000) | 31487.000 (28698.000-39714.000) | +9.29 | 10 | PASS | overlaps |
| mixed-kind-retry | per-function -> shared-queue | post-GC heap | 27726944.000 (27726232.000-27727200.000) | 27727680.000 (27726808.000-27727832.000) | +0.00 | 10 | PASS | overlaps |
| mixed-kind-retry | shared-queue -> per-function | steady p99 | 4.931 (4.566-5.532) | 4.970 (4.493-5.353) | +0.79 | 5 | PASS | overlaps |
| mixed-kind-retry | shared-queue -> per-function | useful throughput | 1517.548 (1491.870-1531.843) | 1525.910 (1506.170-1540.088) | +0.55 | 5 | PASS | overlaps |
| mixed-kind-retry | shared-queue -> per-function | cpu per useful completion | 34574.000 (26809.000-44807.000) | 26553.000 (25967.000-41928.000) | -23.20 | 10 | PASS | overlaps |
| mixed-kind-retry | shared-queue -> per-function | post-GC heap | 27726696.000 (27726360.000-27727448.000) | 27727392.000 (27726696.000-27727672.000) | +0.00 | 10 | PASS | overlaps |
| queued | per-function -> shared-queue | steady p99 | 53.246 (44.741-100.077) | 59.203 (32.186-93.841) | +11.19 | 5 | MISS | overlaps |
| queued | per-function -> shared-queue | useful throughput | 459.556 (456.130-481.468) | 467.417 (456.232-478.626) | +1.71 | 5 | PASS | overlaps |
| queued | per-function -> shared-queue | cpu per useful completion | 78878.000 (69868.000-104438.000) | 70113.000 (67624.000-113636.000) | -11.11 | 10 | PASS | overlaps |
| queued | per-function -> shared-queue | post-GC heap | 27662504.000 (27661792.000-27672608.000) | 27662936.000 (27661880.000-27672264.000) | +0.00 | 10 | PASS | overlaps |
| queued | shared-queue -> per-function | steady p99 | 78.889 (65.959-83.886) | 82.678 (62.886-100.023) | +4.80 | 5 | PASS | overlaps |
| queued | shared-queue -> per-function | useful throughput | 460.192 (455.169-477.955) | 456.341 (450.964-480.637) | -0.84 | 5 | PASS | overlaps |
| queued | shared-queue -> per-function | cpu per useful completion | 69868.000 (66945.000-76400.000) | 78947.000 (66555.000-124113.000) | +12.99 | 10 | MISS | overlaps |
| queued | shared-queue -> per-function | post-GC heap | 27662408.000 (27661232.000-27670544.000) | 27662528.000 (27662208.000-27669768.000) | +0.00 | 10 | PASS | overlaps |
| saturated | per-function -> shared-queue | steady p99 | 61.369 (61.225-61.474) | 61.472 (61.378-61.555) | +0.17 | 5 | PASS | overlaps |
| saturated | per-function -> shared-queue | useful throughput | 717.876 (697.963-724.395) | 679.137 (672.114-693.052) | -5.40 | 5 | PASS | disjoint |
| saturated | per-function -> shared-queue | cpu per useful completion | 83565.000 (77305.000-108882.000) | 100413.000 (75449.000-111896.000) | +20.16 | 10 | MISS | overlaps |
| saturated | per-function -> shared-queue | post-GC heap | 27771144.000 (27768672.000-27775000.000) | 27772800.000 (27766744.000-27774936.000) | +0.01 | 10 | PASS | overlaps |
| saturated | shared-queue -> per-function | steady p99 | 61.947 (61.464-61.987) | 61.991 (61.962-62.003) | +0.07 | 5 | PASS | overlaps |
| saturated | shared-queue -> per-function | useful throughput | 635.305 (630.343-653.817) | 653.198 (644.345-657.197) | +2.82 | 5 | PASS | overlaps |
| saturated | shared-queue -> per-function | cpu per useful completion | 126903.000 (81967.000-129549.000) | 99317.000 (85731.000-128283.000) | -21.74 | 10 | PASS | overlaps |
| saturated | shared-queue -> per-function | post-GC heap | 27777040.000 (27769200.000-27778160.000) | 27773384.000 (27769600.000-27776312.000) | -0.01 | 10 | PASS | overlaps |
| switch-under-load | per-function -> shared-queue | steady p99 | 250.866 (250.208-251.328) | 251.231 (249.151-251.459) | +0.15 | 5 | PASS | overlaps |
| switch-under-load | per-function -> shared-queue | useful throughput | 940.291 (935.424-961.909) | 949.030 (928.781-966.014) | +0.93 | 5 | PASS | overlaps |
| switch-under-load | per-function -> shared-queue | cpu per useful completion | 66528.000 (42753.000-85470.000) | 62893.000 (49648.000-86132.000) | -5.46 | 10 | PASS | overlaps |
| switch-under-load | per-function -> shared-queue | post-GC heap | 27859392.000 (27795000.000-27863816.000) | 27860016.000 (27792592.000-27863824.000) | +0.00 | 10 | PASS | overlaps |
| switch-under-load | shared-queue -> per-function | steady p99 | 253.105 (252.592-253.343) | 251.820 (251.441-253.428) | -0.51 | 5 | PASS | overlaps |
| switch-under-load | shared-queue -> per-function | useful throughput | 978.227 (968.349-980.520) | 993.821 (982.306-1006.979) | +1.59 | 5 | PASS | disjoint |
| switch-under-load | shared-queue -> per-function | cpu per useful completion | 61149.000 (40832.000-77836.000) | 48270.000 (39714.000-69218.000) | -21.06 | 10 | PASS | overlaps |
| switch-under-load | shared-queue -> per-function | post-GC heap | 27860952.000 (27804904.000-27873024.000) | 27851728.000 (27795832.000-27861984.000) | -0.03 | 10 | PASS | overlaps |
| unqueued | per-function -> shared-queue | steady p99 | 4.088 (3.786-4.285) | 3.932 (3.822-3.991) | -3.82 | 5 | PASS | overlaps |
| unqueued | per-function -> shared-queue | useful throughput | 467.398 (451.516-471.048) | 466.675 (451.009-471.461) | -0.15 | 5 | PASS | overlaps |
| unqueued | per-function -> shared-queue | cpu per useful completion | 70859.000 (59422.000-102651.000) | 68551.000 (67854.000-70921.000) | -3.26 | 10 | PASS | overlaps |
| unqueued | per-function -> shared-queue | post-GC heap | 27660552.000 (27660496.000-27660648.000) | 27660760.000 (27660536.000-27661000.000) | +0.00 | 10 | PASS | overlaps |
| unqueued | shared-queue -> per-function | steady p99 | 4.086 (3.967-4.226) | 4.214 (3.887-4.677) | +3.12 | 5 | PASS | overlaps |
| unqueued | shared-queue -> per-function | useful throughput | 466.382 (451.414-470.701) | 467.593 (451.869-470.642) | +0.26 | 5 | PASS | overlaps |
| unqueued | shared-queue -> per-function | cpu per useful completion | 68610.000 (67969.000-79716.000) | 84961.000 (59829.000-88495.000) | +23.83 | 10 | MISS | overlaps |
| unqueued | shared-queue -> per-function | post-GC heap | 27660768.000 (27660672.000-27660976.000) | 27660776.000 (27660680.000-27660896.000) | +0.00 | 10 | PASS | overlaps |

## Return to baseline after 1000 switches, against a no-switch control phase

| figure | switched phase | no-switch control | delta |
|---|---|---|---|
| pending at end | 399 | 400 | -1 (-0.25 %) |
| largest per-function reservation difference | - | - | 1 (+100.00 %) |
| live indexes at end | 1 | 1 | +0 |
| max live indexes during the run | 2 | - | - |
| indexes created / cleared | 1001 / 1000 | - | - |
| admitted | 4178 | 4204 | -0.62 % |
| completed | 3777 | 3802 | -0.66 % |
| useful | 3777 | 3802 | -0.66 % |
| work conserved (admitted == closure) | True | True | - |
| post-GC heap | 27.99 MB | 27.91 MB | +0.31 % |
| switch pause p50 / p99 / max | 0.111 / 0.266 / 0.789 ms | - | - |

### Work conservation across every measured run

- workload runs that conserve work (admitted == completed + expired + removed + rejected + pending + claimed + submitting + in flight): **240 of 240**
- runs with a driver failure: **0**
- runs whose sample cap was reached: **0**
- switch events emitted: **164**


## Workload coverage, and what the harness cannot reach

Spec §11 lists ten workload families. All ten are implemented as profiles and all ten ran; the
list below says what each one actually exercises and, where a family is only partly reachable from
a controlled harness, exactly which part is missing. Nothing is silently skipped.

| §11 workload | profile(s) | reached here | not reachable from this harness |
|---|---|---|---|
| 1. one function, short service, low load | `low-load` | the normal path end to end | — |
| 2. one saturated function: useful capacity, fast refusal, producer/scheduler contention | `saturated` | admission refusal at `maxPending`, queue-deadline expiry, one slot of capacity | — |
| 3. one very active function and 50/500 sporadic ones: fairness and latency of the least active | `hot-plus-50-sporadic`, `hot-plus-500-sporadic` | per-function p50/p95/p99 and expiry counts for every one of the 51/501 functions | — |
| 4. heterogeneous durations and bursts | `heterogeneous-burst` | three duration classes with a sub-millisecond tail, a 1500-ticket burst inside the window | "starts" (cold/warm container start) is a deployment property, not a queue one |
| 5. a backlog of functions without capacity/readiness, few ready: head-of-line blocking | `head-of-line-blocking` | 6 functions, 5 of them readiness-blocked, one ready | — |
| 6. mixed SYNC/ASYNC, retry and offload classes | `mixed-kind-retry` | 50 % SYNC, 20 % retry rate, per-kind counts and latency | **offload**: an `AttemptCoordinator`/dispatch-mode path in the control plane, not on this module's classpath |
| 7. payloads of different sizes, many functions, churn and traffic stop: memory, indexes, drain | `churn-drain` | 20 functions, 4 KiB vs 64 B payloads, half the sporadic functions removed and re-registered mid-run, offering stopped 1.8 s into the window so the queue drains | — |
| 8. capacity changes under load: progress and protocol correctness | `capacity-change` | effective concurrency raised 2 → 8 mid-window | — |
| 9. manual scheduler changes in both directions under continuous load, burst and a backlog with retry/blocked readiness: invocation continuity, dispatch pause, latency, temporary memory, cleanup after repeated changes | `switch-under-load` (and the pause sweep and the 1000-switch phase) | both directions, 9 functions of which 4 are readiness-blocked, a 1000-ticket burst, a 10 % retry rate | — |
| 10. no-queue mode against queued mode at supported load: the cost of shared ownership and fairness | `unqueued`, `queued` | same offered rate (300/s) and same contract, one profile that never queues and one whose capacity is low enough that a small queue forms; the difference is reported as CPU, allocations and p99 per useful completion | the real **direct/no-queue admission path** is a control-plane decision (`InvocationService`/quota), not a mode of this engine: what is compared here is the engine-level approximation, and it is labelled as such rather than presented as the control-plane comparison |

Also reached, beyond the ten families:

- **the pause sweep** over `budgets.json`'s `switchBacklogSizes` `[0, 100, 1000, 10000]`, five
  repetitions each with the direction alternated, against a saturated function holding that
  backlog while completions keep churning through it;
- **the 1000-switch return-to-baseline phase**, run twice — once with the switches, once as a
  no-switch control over the same wall clock, corpus and offered rate.

The NanoLab and HTTP layers that §11 asks for *after* the controlled harness are **not** in this
task: they are Task 13's (`NANOLAB.md`, `SchedulerSwitchHttpTest`) and are not claimed here.

### Caps and omissions, stated rather than hidden

- The harness records at most 2^20 latency samples per run (`sampleCapReached` in every sample
  line says whether that bound was hit; it was not, in any run).
- Per-function detail is emitted only for functions that received work in the window, so an
  untouched function has no row rather than a row of zeros.
- Wall-clock offsets in a profile (`burstAtMs`, `churnAtMs`, `capacityChangeAtMs`, `stopAtMs`) are
  measured from the start of the run, not from the window: with a 1500 ms warm-up and a 2500 ms
  window, an offset of 2500 ms lands 1000 ms into the measured window.
- `budgets.json`'s `repetitions: 5` is used for every arm and every workload. No arm or workload
  ran fewer.
- **No 60-minute soak was run.** The plan assigns that to Task 13 (plan line 837, against Task
  13's frozen artifact). `switchesInSoak: 1000` is used here for what this task's brief asks: the
  switch *count* and the return-to-baseline check after it.

## Limitations of these numbers

- **One host, one JVM, one heap.** All figures come from the machine named in the `header` line of
  `raw/full.jsonl`, on a fixed pre-touched 1 GiB heap, in one JVM. Absolute timings do not transfer
  to a different host or heap; the arm-to-arm comparisons within a repetition are what the budgets
  are read against.
- **The contract deadline is declared per workload, not per call kind.** SYNC and ASYNC share one
  figure, so the per-kind numbers compare observed latency against one declared SLO rather than
  against two different caller timeouts.
- **The service model is deterministic per function** (a whole number of milliseconds plus a fixed
  sub-millisecond tail). It has no provider latency, no cold start and no jitter beyond that tail;
  those belong to Task 13's end-to-end layer.
- **`processCpuTime` is quantised to 10 ms** on Linux, so `cpuPerUsefulCompletion` is coarse for
  low-throughput workloads. The workloads where it matters (saturation, switch under load) complete
  thousands of items per window, where the quantisation is a small fraction. The value is
  monotonic-consistent across arms because every arm is measured the same way.
- **Allocations cover every thread**, harness included, and the harness's own per-arrival
  bookkeeping is inside the figure. Both arms pay it, and the payload allocation is charged to the
  workload that declares a payload.
- **The counted population for the pause max is every measured switch** — sweep, workloads and the
  1000-switch phase — but warm-up switches are excluded, because a warm-up is not a measurement.
  The p99 is read from the 1000-switch phase alone: it is the only population of this campaign
  large enough for a p99 to mean anything, and a p99 over five samples is just the maximum wearing
  a different name.
- **`maxSwitchPreparationMs` is not separately instrumented.** The engine does not expose the
  preparation phase apart from the switch as a whole, so the total switch duration is reported
  against it: the total contains the preparation, so a total inside the budget is evidence the
  preparation is too. That is an upper bound, and it is stated as one rather than as a measurement
  of preparation.


## Provenance: every artifact, and the command that produced it

| artifact | command | what it holds |
|---|---|---|
| `raw/full.jsonl` (+`raw/full.err`) | `./run.sh --label=full` | the campaign: pause sweep, all twelve workloads × four arms × five repetitions, and the 1000-switch return-to-baseline phase. 260 `sample` lines, 164 `switch` lines, one `baseline`, one `summary`, one `header` |
| `raw/smoke.jsonl` (+`raw/smoke.err`) | `./run.sh --label=smoke --parts=backlog --backlogs=100 --repetitions=1` | the end-to-end proof that the harness works: one backlog size, one repetition |
| `raw/diagnostic-hol-window-1200.jsonl`, `…-4000.jsonl`, `…-10000.jsonl` | `./run.sh --label=win<N> --parts=profiles --profiles=head-of-line-blocking --repetitions=3 --window-ms=<N>` (renamed after the run) | the window-length diagnostic on the head-of-line-blocking tail: +42 % at 1.2 s, +14.9 % at 4 s, +3.9 % at 10 s |
| `raw/diagnostic-cc-window-4000.jsonl`, `…-10000.jsonl` | `./run.sh --label=diagcc<N> --parts=profiles --profiles=capacity-change --repetitions=3 --window-ms=<N>` (renamed after the run) | the window-length diagnostic on the one distinguishable miss — see above |
| `raw/host-quietness.txt` | written by hand from `/proc/loadavg` samples taken during the diagnostics and the tail of the preceding run, plus the readings either side of the campaign | what was checked about the host, and the coverage limit of that sampling |

`raw/full.jsonl`'s `header` line records the revision (`a7e7c47c…`), the JVM and host, the max
heap, and the `budgets.json` values the harness read — so the artifact states the thresholds it
was checked against rather than leaving them to be inferred. `run.sh` also passes the harness
source's own sha256, which is in that same line, because the harness is data that changes
independently of the repository revision.

Every table in this file is the output of:

```bash
python3 summarize.py raw/full.jsonl
```

and every budget row in it is read from `budgets.json` by that script, not retyped.

