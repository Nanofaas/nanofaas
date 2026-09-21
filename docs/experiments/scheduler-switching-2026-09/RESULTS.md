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
- **The steady figure is the trailing 2 000 ms of the span**, selected by a rule fixed before
  the settlement campaign ran and reading only the queue depth, never the p99 it segments; the
  transient-inclusive figure is reported beside it for every arm. How that rule was arrived at,
  and the two defects this investigation exposed, are in the section after the tables.
- **A delta over budget is not automatically an effect.** Each arm's five repetitions are reported
  as a range; arms whose ranges overlap are not separated by this measurement, and the brief's own
  instruction for that case is to declare the result not distinguishable rather than to read the
  delta as a regression. The regression table carries both facts: the delta against the budget, and
  whether the arms' ranges are disjoint. **Read that phrase as a statement about the instrument:**
  for p99 and CPU the arms' own spread is two to three times the budget, so the test could not
  have separated a real effect of the budget's size — the resolving-power section measures exactly
  how much it could have seen.
- **Same offered rate, not the same arrival sequence.** The arrival process is seeded from
  `(workload, repetition)`, which is the same seed for the four arms of one repetition — but it does
  **not** give them the same arrivals. One `Random` is consumed by three draws per arrival (the
  inter-arrival interval, the SYNC/ASYNC kind, the function), so as soon as one arm's retry or
  refill path fires an extra offer, every later draw in that arm shifts. Measured on the settlement
  artifact: the four arms' `offered` counts differ in **54 of 60** (workload, repetition) groups, by
  up to 301 tickets (`switch-under-load`, repetition 3: 10 680 against 10 981). Only `low-load` stays
  in lockstep, because at 20/s no extra path fires. An earlier draft of this file claimed identical
  arrivals and one piece of the host-quietness evidence leaned on it; both are corrected. This is
  also the design lever a genuinely paired rerun would pull, and it is deliberately left for the
  step that owns it rather than half-applied here.
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

The shorter run used while the harness was being brought up — one backlog size, one repetition,
one arm, which is exactly what `raw/smoke.jsonl` contains. The settlement campaign's own command is
in the provenance table below:

```bash
./run.sh --label=smoke --parts=backlog --backlogs=100 --repetitions=1
```

A full campaign run is a foreground measurement: no Gradle build, no other sampling run, nothing
else CPU-bound. `run.sh` stops the Gradle daemon before the measured JVM starts for that reason —
for a latency, CPU or post-GC heap figure a concurrent run does not corrupt a file, it corrupts
the number. `raw/host-quietness.txt` records what was checked, the load average observed, and the
part of each campaign the load sampling does **not** cover; the samples themselves are committed
beside it, so those figures can be checked rather than taken on trust. In the artifacts themselves,
the useful-throughput spread across the five repetitions has a median of 3.12 % over the 48
(workload, arm) pairs, which is the shape of an undisturbed host.

## The headline: every frozen value, with the artifact it came from

Generated by `python3 summarize.py raw/full.jsonl` and `python3 summarize.py raw/steady.jsonl`; the source column names the figure in the artifact each observed value was read from, and the artifact column names which campaign measured it. All eleven values `budgets.json` freezes are here, each with its verdict.

| budget | artifact | frozen | observed | verdict |
|---|---|---|---|---|
| `maxSwitchPauseMs` | `raw/full.jsonl` | 250 ms | 3.984 ms — max over 1 140 measured switches | PASS |
| `maxSwitchPauseMs` | `raw/steady.jsonl` (corroborating) | 250 ms | 0.278 ms — max over 120 switches | PASS |
| `maxSwitchPauseP99Ms` | `raw/full.jsonl` | 100 ms | 0.266 ms — p99 over the 1 000-switch phase | PASS |
| `maxSwitchPreparationMs` | `raw/full.jsonl` | 2 000 ms | 3.984 ms — total switch duration, an upper bound on preparation | PASS |
| `maxLiveStrategyIndexes` | `raw/full.jsonl` | 2 | 2 | PASS |
| `switchesInSoak` | `raw/full.jsonl` | 1 000 | 1 000 committed, 0 refused | PASS |
| `repetitions` | both | 5 | 5 samples per (workload, arm), all 48 pairs | PASS |
| `switchBacklogSizes` | `raw/full.jsonl` | [0, 100, 1000, 10000] | all four swept, 5 repetitions each | PASS |
| `maxSteadyP99RegressionPercent` | `raw/steady.jsonl` | 5 % | worst +9.51 %; 4 of 22 measurable comparisons over budget, 0 separable | over budget; **no regression established — the test cannot resolve 5 %** |
| `maxUsefulThroughputRegressionPercent` | `raw/steady.jsonl` | 5 % | worst +2.09 % | PASS |
| `maxCpuPerCompletionRegressionPercent` | `raw/steady.jsonl` | 10 % | worst +22.06 %; 3 of 24 over budget, 0 separable | over budget; **no regression established — the test cannot resolve 10 %** |
| `maxPostGcHeapRegressionPercent` | `raw/steady.jsonl` | 10 % | worst +0.01 % | PASS |
| — (2 of 4 `churn-drain` comparisons) | `raw/steady.jsonl` | — | `churn-drain` stops its traffic by design, so its steady *window* holds no arrivals. Its two window rows are not measurable; its whole-run rows (CPU, heap) are measured and **pass**, which is the plan-mandated comparison | **2 not measurable, 2 pass** |


### The mechanism budgets pass, unchanged

The pause, preparation, index-count and switch-count rows are rounds 1–2's, recorded as passing and
**not re-run to look for a different answer**; the instruction and its provenance are restated in
`STATO.md` so it is verifiable inside the repository rather than only in the campaign ledger. The
settlement campaign corroborates the pause figure independently — 0.278 ms over its own 120
switches — without being asked to. Two things about that figure are worth stating because they make
it the harshest available reading: the headline maximum is the **first switch of the process**,
against an empty engine and a cold JIT, and it still clears the budget by 60×.

The 1 000-switch return-to-baseline phase has been re-run against the committed harness (the
artifact is `raw/baseline.jsonl`, harness digest in its own header); it passes at least as cleanly as
the round-1 measurement and its numbers are in the table further down.

### The queue converges, so the arms are comparable once settled

The question the settling correction turns on is not whether the p99 converged but whether the
*arms* did. Every one of the 24 switched-vs-control pairs has a settled queue level that meets its
target's own settled range — `capacity-change` at +2.2 % and +15.8 %, `saturated` at +0.0 % and
+1.5 %, `switch-under-load` at −1.4 % and −4.3 %, `head-of-line-blocking` at +0.8 % and −1.7 %. The
rest are within five tickets of their control in absolute terms (`queued` is the largest, at +5
tickets on a control level of 4; the others are +0 or +1), and the ticket-scale cases are the ones
where a percentage would be a ratio of small integers and would say nothing. The table below is the
evidence, per workload and per arm, with the depth trajectory it was read from in
`raw/steady.jsonl`. So the arms are comparable at steady state, and the steady p99 comparison is a
meaningful one — which is the first of the two outcomes the ruling named, not the second.

### What the two over-budget rows mean, and what this campaign's resolution is

Seven comparisons exceed their threshold — four p99 and three CPU — and every one sits inside its
arms' own five-repetition ranges. Read as a verdict, that says "not distinguishable". Read as
evidence, it says something weaker and more useful:

> **No regression has been established at this campaign's resolution, and the resolution is coarser
> than two of the budgets.** The budgets are 5 % (p99), 5 % (throughput), 10 % (CPU) and 10 %
> (heap); the arms' own median spread across five repetitions is **16.1 % for p99** and **26.9 % for
> CPU**, against 7.1 % for throughput and 0.0 % for heap. A range-overlap test cannot separate arms
> that disagree with themselves by two to three times the effect it is looking for.

That is a statement about the instrument, not about the code, and the difference matters: this
campaign is not evidence that manual switching is free. It is evidence that this instrument could
**not** have seen a 5 % p99 effect or a 10 % CPU effect if one were there — and that it could and
did clear those same budgets on post-GC heap, where the arms' spread is 0.0 % and the test has
teeth. The resolving-power table below measures this directly, on this artifact, so the null can be
read for what it is worth rather than taken as reassurance.

The disposition is unchanged: the brief maps «le differenze sono entro la dispersione» to
«dichiarare risultato non distinguibile e conservare entrambi gli scheduler», no threshold moves, no
strategy is eliminated. Only the **claim** is corrected.

### Eligibility, tested per metric

Two of the four budgets are window-derived — the steady p99 and the steady useful throughput, read
off the trailing 2 000 ms — and only they are gated on the settling rule of the section below and on
the window actually holding arrivals. The other two are whole-run quantities by construction: CPU
per useful completion is span CPU over span completions, and post-GC heap is measured after the run.
Neither has a "steady" form, so neither can be unmeasurable for want of steady arrivals. An earlier
draft labelled them "steady" and exempted a workload whose traffic stops; that was a label defect
and a coverage gap at once, and it dropped the plan-mandated memory comparison from `churn-drain`,
which the corrected table now reports as a **pass** (CPU +7.26 % / +7.67 %, heap +0.00 % / +0.00 %,
all inside a 10 % budget). Only `churn-drain`'s two window rows are genuinely unmeasurable, and they
are reported that way rather than as passes.

### The settling correction, and the defect it exposed

Round 3 exists because round 1's window contained the arm's settling, so it did not measure what a
budget named *maxSteady*P99Regression names. The correction is a rule fixed **before** the campaign
ran, reading only the queue depth — never the p99 it segments — with both figures reported side by
side for every arm. It is set out in full below.

Chasing it also exposed a bug in this harness's own workload generator: the `capacity-change`
workload asked for an effective concurrency of **8** against a configured **2**, and
`FunctionCapacityState.setEffectiveConcurrency` clamps to `[1, configured]`, so the request was
silently refused. For rounds 1 and 2 that workload never changed capacity despite being named for
one, and the comparison round 1 read as its one distinguishable p99 miss was measured on it — a
miss that was, on inspection, a property of a mixture in an arm that was running two strategies
across one window. It is now a real change (2 → 1, then a restore) and the harness reads the
effective value back into the artifact, so it cannot recur unnoticed. Under the corrected workload
and the corrected protocol, that workload's steady p99 comparison is **+0.10 % and −5.82 %**.

### No winner is declared

The per-workload table carries both strategies side by side and they differ substantially on some
workloads. Those are measurements of two preserved implementations on one corpus, not a ranking:
spec §11 — *«Entrambe le strategie esistenti restano supportate… Non servono a cercare uno scheduler
dominante, eliminare una delle due strategie o attivare una selezione automatica»*, and *«risultati
simili o un vantaggio dell'altra strategia non autorizzano a eliminarla»*. Nothing here recommends
the removal of either.

## How the steady figure was arrived at, and what looking for it exposed

**The strongest evidence in this file is a defect this harness produced and then had to have
removed.** Round 1 measured the switch in the *middle* of the measured window and compared each
switched arm against the strategy it *started* on. An arm that runs per-function for half its
window and shared-queue for the other half therefore has a latency distribution that is a mixture
of the two, and comparing that mixture against a never-switched per-function arm produced a
catastrophic p99 "regression" on `head-of-line-blocking` that did not exist. Round 1 reported it as +3851 %; that figure's artifact was overwritten by the corrected run before it was committed,
so this file does not ask to be believed about it. Instead it computes the same quantity from the
artifact that does exist, under `python3 summarize.py raw/steady.jsonl`:

| `head-of-line-blocking` steady p99 | compared against | control ms | switched ms | delta |
|---|---|---|---|---|
| per-function -> shared-queue | **wrong** (the strategy it started on) | 3.133 | 122.368 | **+3805.3 %** |
| shared-queue -> per-function | **wrong** (the strategy it started on) | 122.947 | 2.997 | −97.6 % |
| per-function -> shared-queue | **right** (the strategy it ends on) | 122.947 | 122.368 | −0.5 % |
| shared-queue -> per-function | **right** (the strategy it ends on) | 3.133 | 2.997 | −4.4 % |

The two mismatched comparisons of one workload differ by more than three thousand per cent while
the matched ones differ by under five, which is the whole defect: the comparison key, not the
switch. **The same harness that can produce a false catastrophic miss can equally produce a false
pass**, and that is why the settling rule below is written down rather than chosen.

Two corrections followed, and neither moves a threshold:

1. **Round 2 (already committed).** The switch lands at the *start* of the measured window, and the
   window opens only once the switch has committed, so the whole window runs on the target strategy
   and the comparison is against that strategy's own no-change arm. This removed the mixture.
2. **Round 3 (this section).** The remaining window still contained the arm's *settling* — the queue
   the previous strategy left behind — so it did not measure what a budget named *maxSteady*P99
   Regression names. The settling segment is now identified and excluded **by a rule fixed before
   the campaign ran, from an observable that is not the p99**.

### The settling rule

The rule reads nothing but the queue depth, so it cannot be steered by the latency it segments:

- **`settledDepth`** — the median of the arm's depth trajectory over the final quarter of the span.
- **settled range** — the `Q1–Q3` interval the arm actually occupies at that point.
- **`settleMillis`** — the last point of the span whose 500 ms moving average was an *outlier* of
  that arm's own settled distribution (Tukey's fence, 1.5 IQR). A moving average because a queue
  depth is a noisy signal that dips to zero and spikes to twice its mean without the queue having
  changed level; a fence rather than a fixed percentage because a fixed percentage around a low
  median rejects a queue that is simply fluctuating.
- **steady window** — the trailing 2 000 ms of the span, the same length round 1 measured, so the
  corrected figure is comparable with the one already on the page.
- **validity** — a workload's steady comparison is read only where *every* arm settled before that
  window opens (`settleMillis ≤ span − 2 000 ms`). Where an arm did not, the steady figure is
  reported as **not measurable** rather than silently read anyway.
- **comparability** — the switched arm's settled range must meet its target no-change arm's. If it
  does, the arms are comparable once settled and the steady p99 comparison is meaningful. If it does
  not, the finding is *not* a window problem and is reported as what it is: a manually switched arm
  carrying a persistently different queue from a never-switched control.

The depth trajectory is sampled every 50 ms by the driver from the harness's own counters, so it
never touches the engine's gate. **Both figures appear side by side for every arm** — the
transient-inclusive one over the whole span and the steady one — so a reader sees the settling
rather than having it hidden by the choice of window.

### The second defect this investigation found: a capacity change that changed nothing

Chasing the settling question turned up a bug in this harness's own workload generator.
`FunctionCapacityState.setEffectiveConcurrency` clamps to `[1, configuredConcurrency]`, so the
`capacity-change` workload — which asked for **8** against a configured **2** — was silently
refused: for the whole of round 1 and round 2 it exercised a workload that never changed capacity,
despite being named for one. It is now a real change in the direction the API supports (2 → 1, then
the restore at 60 % of the span), and the harness reads the effective concurrency back and writes it
to the artifact, so it cannot recur unnoticed:

```
[bench] capacity change requested 1, effective now: hot-0=1 hot-1=1
[bench] capacity restored to 2, effective now: hot-0=2 hot-1=2
```

Round 1's `capacity-change` row — including the one distinguishable p99 miss — was therefore
measured on a workload that was not the workload it claimed to be. That is stated here rather than
patched over, and the corrected campaign is what the table below reports.

### The CPU budget's resolution

Round 1 reported `maxCpuPerCompletionRegressionPercent` as missed on 8 of 24 comparisons, none
distinguishable, and attributed that to the resolution of the measurement. That attribution was
right and the defect was fixable, so it is fixed rather than declared away:

| clock | 10 ms of busy work measures | verdict |
|---|---|---|
| `OperatingSystemMXBean.getProcessCpuTime` | 10 000 000 (6×) and 20 000 000 (2×) for a 10 000 000 ns loop | a 10 ms step, and it does not report the interval faithfully inside that step — cannot resolve 10 % on a workload accumulating a few hundred ms of CPU |
| `ThreadMXBean.getCurrentThreadCpuTime` | 10 000 640–10 007 536 for the same 10 ms loop; 978 112–1 005 168 for a 1 ms loop | **sub-microsecond resolution** — three orders of magnitude finer than the budget |

Round 3 therefore reports CPU per useful completion from the **sum of every live thread's CPU
time**, which is the same quantity as process CPU at the thread clock's resolution, and the process
figure is emitted beside it in every sample so the two can be compared in the artifact itself. The
budget is read from the resolvable one, and the resolution claim above is a measurement
(`java ClockTest`, recorded in `raw/clock-resolution.txt`), not an assertion.

## The tables the corrected protocol produced

## Does the queue converge? — the settling rule, read off the depth trajectory

`settledDepth` is the median of the final quarter of the trajectory; `settleMillis` is the last point of the span still outside ±25 % of it. Both are backlog-only figures — the segmentation cannot be steered by the latency it segments. The steady window is the trailing 2000 ms, valid where every arm settled before it opens.

| workload | arm | settled depth | settled Q1-Q3 | settle ms (worst rep) | settled before the steady window |
|---|---|---|---|---|---|
| low-load | per-function (no change) | 0 | -2-2 | 0 | yes |
| low-load | shared-queue (no change) | 0 | -2-2 | 0 | yes |
| low-load | per-function -> shared-queue | 0 | -2-2 | 0 | yes |
| low-load | shared-queue -> per-function | 0 | -2-2 | 0 | yes |
| saturated | per-function (no change) | 97 | 74-124 | 0 | yes |
| saturated | shared-queue (no change) | 94 | 70-120 | 50 | yes |
| saturated | per-function -> shared-queue | 94 | 66-124 | 0 | yes |
| saturated | shared-queue -> per-function | 98 | 74-123 | 0 | yes |
| hot-plus-50-sporadic | per-function (no change) | 3 | -2-10 | 0 | yes |
| hot-plus-50-sporadic | shared-queue (no change) | 4 | -2-10 | 100 | yes |
| hot-plus-50-sporadic | per-function -> shared-queue | 3 | -2-10 | 0 | yes |
| hot-plus-50-sporadic | shared-queue -> per-function | 4 | 0-8 | 0 | yes |
| hot-plus-500-sporadic | per-function (no change) | 5 | -0-10 | 0 | yes |
| hot-plus-500-sporadic | shared-queue (no change) | 5 | -0-12 | 0 | yes |
| hot-plus-500-sporadic | per-function -> shared-queue | 5 | -0-12 | 0 | yes |
| hot-plus-500-sporadic | shared-queue -> per-function | 5 | -0-12 | 0 | yes |
| heterogeneous-burst | per-function (no change) | 1 | -0-4 | 1750 | yes |
| heterogeneous-burst | shared-queue (no change) | 2 | -2-6 | 1750 | yes |
| heterogeneous-burst | per-function -> shared-queue | 1 | -2-5 | 1750 | yes |
| heterogeneous-burst | shared-queue -> per-function | 2 | -0-4 | 1750 | yes |
| head-of-line-blocking | per-function (no change) | 116 | 87-148 | 0 | yes |
| head-of-line-blocking | shared-queue (no change) | 124 | 90-162 | 0 | yes |
| head-of-line-blocking | per-function -> shared-queue | 125 | 89-159 | 0 | yes |
| head-of-line-blocking | shared-queue -> per-function | 114 | 87-143 | 0 | yes |
| mixed-kind-retry | per-function (no change) | 3 | -2-10 | 0 | yes |
| mixed-kind-retry | shared-queue (no change) | 3 | -1-7 | 0 | yes |
| mixed-kind-retry | per-function -> shared-queue | 3 | -1-7 | 2600 | yes |
| mixed-kind-retry | shared-queue -> per-function | 3 | -1-7 | 0 | yes |
| churn-drain | per-function (no change) | 0 | -2-2 | 5000 | yes |
| churn-drain | shared-queue (no change) | 0 | -2-2 | 4950 | yes |
| churn-drain | per-function -> shared-queue | 0 | -2-2 | 5000 | yes |
| churn-drain | shared-queue -> per-function | 0 | -2-2 | 5050 | yes |
| capacity-change | per-function (no change) | 10 | -8-29 | 5700 | yes |
| capacity-change | shared-queue (no change) | 68 | 47-89 | 5150 | yes |
| capacity-change | per-function -> shared-queue | 70 | 48-92 | 5200 | yes |
| capacity-change | shared-queue -> per-function | 11 | -4-28 | 5700 | yes |
| switch-under-load | per-function (no change) | 187 | 156-212 | 3500 | yes |
| switch-under-load | shared-queue (no change) | 174 | 135-207 | 5300 | yes |
| switch-under-load | per-function -> shared-queue | 171 | 134-202 | 3600 | yes |
| switch-under-load | shared-queue -> per-function | 179 | 146-218 | 5650 | yes |
| unqueued | per-function (no change) | 0 | -2-2 | 0 | yes |
| unqueued | shared-queue (no change) | 0 | -2-2 | 0 | yes |
| unqueued | per-function -> shared-queue | 1 | -2-2 | 0 | yes |
| unqueued | shared-queue -> per-function | 0 | -2-2 | 0 | yes |
| queued | per-function (no change) | 4 | -7-20 | 5900 | yes |
| queued | shared-queue (no change) | 4 | -6-17 | 2700 | yes |
| queued | per-function -> shared-queue | 9 | -6-20 | 2900 | yes |
| queued | shared-queue -> per-function | 5 | -7-17 | 5600 | yes |

## The budgets on the corrected protocol, eligibility tested per metric

`window` metrics are read off the trailing 2000 ms and are eligible only where both arms settled before that window opened (the settling table above) *and* the window holds arrivals. `whole run` metrics — CPU per useful completion, which is span CPU over span completions, and post-GC heap, measured after the run — have no steady form, so they have one column and no transient-inclusive counterpart, and a workload that stops its traffic is not an obstacle to them and is not exempted from them.

| workload | switch | metric | nature | control | switched | delta % | budget % | verdict | dispersion | transient-inclusive delta % |
|---|---|---|---|---|---|---|---|---|---|---|
| capacity-change | per-function -> shared-queue | steady p99 | window | 146.808 (143.246-148.924) | 146.961 (140.884-149.082) | +0.10 | 5 | PASS | overlaps | +0.10 |
| capacity-change | per-function -> shared-queue | steady useful throughput | window | 1017.500 (978.500-1022.500) | 1014.000 (955.500-1024.000) | -0.34 | 5 | PASS | overlaps | -0.34 |
| capacity-change | per-function -> shared-queue | thread cpu per useful completion | whole run | 46465.000 (45125.000-68870.000) | 56717.000 (54746.000-68639.000) | +22.06 | 10 | MISS | overlaps | — (no steady form) |
| capacity-change | per-function -> shared-queue | post-GC heap | whole run | 37153328.000 (37146160.000-37159800.000) | 37151376.000 (37147512.000-37155176.000) | -0.01 | 10 | PASS | overlaps | — (no steady form) |
| capacity-change | shared-queue -> per-function | steady p99 | window | 51.942 (44.673-72.808) | 48.918 (38.809-79.472) | -5.82 | 5 | PASS | overlaps | -5.82 |
| capacity-change | shared-queue -> per-function | steady useful throughput | window | 1113.000 (1108.500-1137.000) | 1105.000 (1102.500-1130.000) | -0.72 | 5 | PASS | overlaps | -0.72 |
| capacity-change | shared-queue -> per-function | thread cpu per useful completion | whole run | 43420.000 (40619.000-51131.000) | 50351.000 (40730.000-56011.000) | +15.96 | 10 | MISS | overlaps | — (no steady form) |
| capacity-change | shared-queue -> per-function | post-GC heap | whole run | 37124256.000 (37123088.000-37126728.000) | 37124472.000 (37121832.000-37132840.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| churn-drain | per-function -> shared-queue | steady p99 | window | — | — | — | 5 | NOT MEASURABLE (no arrivals in the steady window) | — | — |
| churn-drain | per-function -> shared-queue | steady useful throughput | window | — | — | — | 5 | NOT MEASURABLE (no arrivals in the steady window) | — | — |
| churn-drain | per-function -> shared-queue | thread cpu per useful completion | whole run | 50298.000 (41486.000-50807.000) | 53950.000 (46792.000-62512.000) | +7.26 | 10 | PASS | overlaps | — (no steady form) |
| churn-drain | per-function -> shared-queue | post-GC heap | whole run | 37118584.000 (37118216.000-37119136.000) | 37118672.000 (37118352.000-37119272.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| churn-drain | shared-queue -> per-function | steady p99 | window | — | — | — | 5 | NOT MEASURABLE (no arrivals in the steady window) | — | — |
| churn-drain | shared-queue -> per-function | steady useful throughput | window | — | — | — | 5 | NOT MEASURABLE (no arrivals in the steady window) | — | — |
| churn-drain | shared-queue -> per-function | thread cpu per useful completion | whole run | 49248.000 (39881.000-57678.000) | 53024.000 (48148.000-55100.000) | +7.67 | 10 | PASS | overlaps | — (no steady form) |
| churn-drain | shared-queue -> per-function | post-GC heap | whole run | 37118584.000 (37118336.000-37119264.000) | 37118712.000 (37118496.000-37119064.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| head-of-line-blocking | per-function -> shared-queue | steady p99 | window | 122.947 (119.397-125.738) | 122.368 (120.244-134.227) | -0.47 | 5 | PASS | overlaps | -0.47 |
| head-of-line-blocking | per-function -> shared-queue | steady useful throughput | window | 107.000 (102.500-119.500) | 106.000 (102.500-118.500) | -0.93 | 5 | PASS | overlaps | -0.93 |
| head-of-line-blocking | per-function -> shared-queue | thread cpu per useful completion | whole run | 318128.000 (286699.000-382791.000) | 296673.000 (286756.000-343966.000) | -6.74 | 10 | PASS | overlaps | — (no steady form) |
| head-of-line-blocking | per-function -> shared-queue | post-GC heap | whole run | 37173472.000 (37171912.000-37184992.000) | 37172344.000 (37169648.000-37184632.000) | -0.00 | 10 | PASS | overlaps | — (no steady form) |
| head-of-line-blocking | shared-queue -> per-function | steady p99 | window | 3.133 (2.714-3.381) | 2.997 (2.776-3.303) | -4.36 | 5 | PASS | overlaps | -4.36 |
| head-of-line-blocking | shared-queue -> per-function | steady useful throughput | window | 112.500 (108.000-122.000) | 112.500 (107.000-122.000) | +0.00 | 5 | PASS | overlaps | +0.00 |
| head-of-line-blocking | shared-queue -> per-function | thread cpu per useful completion | whole run | 308949.000 (263354.000-328710.000) | 300030.000 (263319.000-323907.000) | -2.89 | 10 | PASS | overlaps | — (no steady form) |
| head-of-line-blocking | shared-queue -> per-function | post-GC heap | whole run | 37171072.000 (37168488.000-37181248.000) | 37170608.000 (37167544.000-37180520.000) | -0.00 | 10 | PASS | overlaps | — (no steady form) |
| heterogeneous-burst | per-function -> shared-queue | steady p99 | window | 4.510 (4.164-4.613) | 4.311 (4.199-4.577) | -4.40 | 5 | PASS | overlaps | -4.40 |
| heterogeneous-burst | per-function -> shared-queue | steady useful throughput | window | 429.500 (402.000-439.500) | 429.500 (400.500-440.500) | +0.00 | 5 | PASS | overlaps | +0.00 |
| heterogeneous-burst | per-function -> shared-queue | thread cpu per useful completion | whole run | 70666.000 (60511.000-79133.000) | 71802.000 (66944.000-76043.000) | +1.61 | 10 | PASS | overlaps | — (no steady form) |
| heterogeneous-burst | per-function -> shared-queue | post-GC heap | whole run | 37154584.000 (37153440.000-37189960.000) | 37154744.000 (37154072.000-37190224.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| heterogeneous-burst | shared-queue -> per-function | steady p99 | window | 4.284 (4.189-4.616) | 4.519 (4.156-4.559) | +5.49 | 5 | MISS | overlaps | +5.49 |
| heterogeneous-burst | shared-queue -> per-function | steady useful throughput | window | 429.500 (401.500-439.000) | 429.000 (406.000-437.500) | -0.12 | 5 | PASS | overlaps | -0.12 |
| heterogeneous-burst | shared-queue -> per-function | thread cpu per useful completion | whole run | 68605.000 (62277.000-83734.000) | 64118.000 (58770.000-70122.000) | -6.54 | 10 | PASS | overlaps | — (no steady form) |
| heterogeneous-burst | shared-queue -> per-function | post-GC heap | whole run | 37147040.000 (37146144.000-37215928.000) | 37147336.000 (37146800.000-37150464.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-50-sporadic | per-function -> shared-queue | steady p99 | window | 5.270 (5.151-7.571) | 5.615 (4.699-7.642) | +6.54 | 5 | MISS | overlaps | +6.54 |
| hot-plus-50-sporadic | per-function -> shared-queue | steady useful throughput | window | 1474.500 (1448.000-1488.500) | 1473.000 (1460.500-1493.500) | -0.10 | 5 | PASS | overlaps | -0.10 |
| hot-plus-50-sporadic | per-function -> shared-queue | thread cpu per useful completion | whole run | 42292.000 (34553.000-51701.000) | 34479.000 (32684.000-45357.000) | -18.47 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-50-sporadic | per-function -> shared-queue | post-GC heap | whole run | 37199136.000 (37198320.000-37200168.000) | 37199296.000 (37198336.000-37201256.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-50-sporadic | shared-queue -> per-function | steady p99 | window | 6.909 (6.616-8.419) | 6.536 (5.975-7.975) | -5.40 | 5 | PASS | overlaps | -5.40 |
| hot-plus-50-sporadic | shared-queue -> per-function | steady useful throughput | window | 1468.500 (1457.500-1493.500) | 1469.000 (1454.000-1493.500) | +0.03 | 5 | PASS | overlaps | +0.03 |
| hot-plus-50-sporadic | shared-queue -> per-function | thread cpu per useful completion | whole run | 43608.000 (33528.000-50774.000) | 41932.000 (36449.000-45717.000) | -3.84 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-50-sporadic | shared-queue -> per-function | post-GC heap | whole run | 37199024.000 (37198520.000-37201728.000) | 37199472.000 (37199144.000-37200728.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-500-sporadic | per-function -> shared-queue | steady p99 | window | 4.702 (3.970-6.970) | 4.551 (3.990-5.486) | -3.21 | 5 | PASS | overlaps | -3.21 |
| hot-plus-500-sporadic | per-function -> shared-queue | steady useful throughput | window | 2222.000 (2156.500-2270.500) | 2223.000 (2156.500-2260.500) | +0.05 | 5 | PASS | overlaps | +0.05 |
| hot-plus-500-sporadic | per-function -> shared-queue | thread cpu per useful completion | whole run | 32039.000 (29571.000-35302.000) | 31312.000 (27848.000-36825.000) | -2.27 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-500-sporadic | per-function -> shared-queue | post-GC heap | whole run | 37425128.000 (37424240.000-37425912.000) | 37425432.000 (37424784.000-37426488.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| hot-plus-500-sporadic | shared-queue -> per-function | steady p99 | window | 4.722 (4.349-5.236) | 5.172 (4.501-5.577) | +9.51 | 5 | MISS | overlaps | +9.51 |
| hot-plus-500-sporadic | shared-queue -> per-function | steady useful throughput | window | 2218.000 (2157.000-2266.500) | 2218.500 (2163.000-2273.500) | +0.02 | 5 | PASS | overlaps | +0.02 |
| hot-plus-500-sporadic | shared-queue -> per-function | thread cpu per useful completion | whole run | 30662.000 (29020.000-33233.000) | 35197.000 (31245.000-36059.000) | +14.79 | 10 | MISS | overlaps | — (no steady form) |
| hot-plus-500-sporadic | shared-queue -> per-function | post-GC heap | whole run | 37425064.000 (37424808.000-37425496.000) | 37425552.000 (37424008.000-37426880.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| low-load | per-function -> shared-queue | steady p99 | window | 2.841 (2.642-3.844) | 2.837 (2.441-3.427) | -0.13 | 5 | PASS | overlaps | -0.13 |
| low-load | per-function -> shared-queue | steady useful throughput | window | 21.500 (16.500-24.500) | 21.500 (16.500-24.500) | +0.00 | 5 | PASS | overlaps | +0.00 |
| low-load | per-function -> shared-queue | thread cpu per useful completion | whole run | 798414.000 (637808.000-877140.000) | 815153.000 (594102.000-924755.000) | +2.10 | 10 | PASS | overlaps | — (no steady form) |
| low-load | per-function -> shared-queue | post-GC heap | whole run | 37140464.000 (37136848.000-37140640.000) | 37140576.000 (37136840.000-37140776.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| low-load | shared-queue -> per-function | steady p99 | window | 2.798 (2.725-3.792) | 2.719 (2.555-3.453) | -2.80 | 5 | PASS | overlaps | -2.80 |
| low-load | shared-queue -> per-function | steady useful throughput | window | 21.500 (16.500-24.500) | 21.500 (16.500-24.500) | +0.00 | 5 | PASS | overlaps | +0.00 |
| low-load | shared-queue -> per-function | thread cpu per useful completion | whole run | 777216.000 (680539.000-952544.000) | 828945.000 (668262.000-875347.000) | +6.66 | 10 | PASS | overlaps | — (no steady form) |
| low-load | shared-queue -> per-function | post-GC heap | whole run | 37137832.000 (37119864.000-37140784.000) | 37140816.000 (37137040.000-37168552.000) | +0.01 | 10 | PASS | overlaps | — (no steady form) |
| mixed-kind-retry | per-function -> shared-queue | steady p99 | window | 5.126 (4.489-6.174) | 4.961 (4.538-5.336) | -3.23 | 5 | PASS | overlaps | -3.23 |
| mixed-kind-retry | per-function -> shared-queue | steady useful throughput | window | 947.500 (945.000-973.500) | 961.000 (938.000-974.500) | +1.42 | 5 | PASS | overlaps | +1.42 |
| mixed-kind-retry | per-function -> shared-queue | thread cpu per useful completion | whole run | 52063.000 (42457.000-56691.000) | 42136.000 (36652.000-44735.000) | -19.07 | 10 | PASS | overlaps | — (no steady form) |
| mixed-kind-retry | per-function -> shared-queue | post-GC heap | whole run | 37106120.000 (37104984.000-37106496.000) | 37105744.000 (37105088.000-37106024.000) | -0.00 | 10 | PASS | overlaps | — (no steady form) |
| mixed-kind-retry | shared-queue -> per-function | steady p99 | window | 4.834 (4.727-4.896) | 4.799 (4.491-5.057) | -0.74 | 5 | PASS | overlaps | -0.74 |
| mixed-kind-retry | shared-queue -> per-function | steady useful throughput | window | 953.500 (940.000-979.500) | 961.000 (943.000-973.500) | +0.79 | 5 | PASS | overlaps | +0.79 |
| mixed-kind-retry | shared-queue -> per-function | thread cpu per useful completion | whole run | 46138.000 (40757.000-52085.000) | 41953.000 (38748.000-53278.000) | -9.07 | 10 | PASS | overlaps | — (no steady form) |
| mixed-kind-retry | shared-queue -> per-function | post-GC heap | whole run | 37106056.000 (37105080.000-37106584.000) | 37105672.000 (37105552.000-37106296.000) | -0.00 | 10 | PASS | overlaps | — (no steady form) |
| queued | per-function -> shared-queue | steady p99 | window | 58.269 (39.099-69.014) | 63.721 (39.106-94.842) | +9.36 | 5 | MISS | overlaps | +9.36 |
| queued | per-function -> shared-queue | steady useful throughput | window | 292.000 (286.500-317.500) | 287.000 (283.000-315.500) | -1.71 | 5 | PASS | overlaps | -1.71 |
| queued | per-function -> shared-queue | thread cpu per useful completion | whole run | 101944.000 (88114.000-110472.000) | 106521.000 (99007.000-131387.000) | +4.49 | 10 | PASS | overlaps | — (no steady form) |
| queued | per-function -> shared-queue | post-GC heap | whole run | 37115704.000 (37114040.000-37123632.000) | 37116024.000 (37114328.000-37124672.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| queued | shared-queue -> per-function | steady p99 | window | 85.933 (53.532-89.211) | 88.290 (56.747-96.242) | +2.74 | 5 | PASS | overlaps | +2.74 |
| queued | shared-queue -> per-function | steady useful throughput | window | 288.000 (279.000-315.500) | 285.500 (280.000-315.500) | -0.87 | 5 | PASS | overlaps | -0.87 |
| queued | shared-queue -> per-function | thread cpu per useful completion | whole run | 108503.000 (90316.000-121430.000) | 115320.000 (106913.000-142575.000) | +6.28 | 10 | PASS | overlaps | — (no steady form) |
| queued | shared-queue -> per-function | post-GC heap | whole run | 37116760.000 (37114336.000-37121664.000) | 37117040.000 (37114920.000-37122416.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| saturated | per-function -> shared-queue | steady p99 | window | 61.412 (60.970-61.658) | 61.403 (61.025-61.495) | -0.01 | 5 | PASS | overlaps | -0.01 |
| saturated | per-function -> shared-queue | steady useful throughput | window | 441.000 (417.500-444.500) | 440.500 (403.500-448.500) | -0.11 | 5 | PASS | overlaps | -0.11 |
| saturated | per-function -> shared-queue | thread cpu per useful completion | whole run | 125606.000 (114996.000-128249.000) | 131434.000 (108523.000-189368.000) | +4.64 | 10 | PASS | overlaps | — (no steady form) |
| saturated | per-function -> shared-queue | post-GC heap | whole run | 37226264.000 (37218384.000-37234136.000) | 37227448.000 (37219736.000-37232152.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| saturated | shared-queue -> per-function | steady p99 | window | 61.926 (61.901-62.002) | 61.964 (61.805-62.019) | +0.06 | 5 | PASS | overlaps | +0.06 |
| saturated | shared-queue -> per-function | steady useful throughput | window | 383.000 (381.000-395.000) | 391.000 (383.000-404.500) | +2.09 | 5 | PASS | overlaps | +2.09 |
| saturated | shared-queue -> per-function | thread cpu per useful completion | whole run | 147681.000 (111712.000-164877.000) | 138013.000 (127456.000-159270.000) | -6.55 | 10 | PASS | overlaps | — (no steady form) |
| saturated | shared-queue -> per-function | post-GC heap | whole run | 37225752.000 (37220384.000-37234400.000) | 37226096.000 (37220104.000-37235928.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| switch-under-load | per-function -> shared-queue | steady p99 | window | 248.615 (243.739-249.675) | 248.916 (248.183-250.193) | +0.12 | 5 | PASS | overlaps | +0.12 |
| switch-under-load | per-function -> shared-queue | steady useful throughput | window | 560.000 (529.500-575.500) | 557.000 (538.000-567.500) | -0.54 | 5 | PASS | overlaps | -0.54 |
| switch-under-load | per-function -> shared-queue | thread cpu per useful completion | whole run | 84058.000 (74234.000-86469.000) | 80978.000 (62165.000-89810.000) | -3.66 | 10 | PASS | overlaps | — (no steady form) |
| switch-under-load | per-function -> shared-queue | post-GC heap | whole run | 37252304.000 (37248640.000-37256952.000) | 37251736.000 (37244640.000-37255016.000) | -0.00 | 10 | PASS | overlaps | — (no steady form) |
| switch-under-load | shared-queue -> per-function | steady p99 | window | 252.759 (249.379-253.536) | 252.829 (248.203-253.421) | +0.03 | 5 | PASS | overlaps | +0.03 |
| switch-under-load | shared-queue -> per-function | steady useful throughput | window | 546.000 (523.500-555.000) | 548.500 (530.000-561.000) | +0.46 | 5 | PASS | overlaps | +0.46 |
| switch-under-load | shared-queue -> per-function | thread cpu per useful completion | whole run | 75565.000 (60697.000-84980.000) | 76194.000 (65607.000-96252.000) | +0.83 | 10 | PASS | overlaps | — (no steady form) |
| switch-under-load | shared-queue -> per-function | post-GC heap | whole run | 37251752.000 (37246360.000-37261848.000) | 37255440.000 (37249800.000-37259712.000) | +0.01 | 10 | PASS | overlaps | — (no steady form) |
| unqueued | per-function -> shared-queue | steady p99 | window | 3.959 (3.592-4.401) | 4.021 (3.719-4.363) | +1.55 | 5 | PASS | overlaps | +1.55 |
| unqueued | per-function -> shared-queue | steady useful throughput | window | 299.000 (274.500-300.500) | 298.500 (273.000-300.500) | -0.17 | 5 | PASS | overlaps | -0.17 |
| unqueued | per-function -> shared-queue | thread cpu per useful completion | whole run | 96718.000 (87743.000-110495.000) | 95304.000 (90281.000-117102.000) | -1.46 | 10 | PASS | overlaps | — (no steady form) |
| unqueued | per-function -> shared-queue | post-GC heap | whole run | 37114536.000 (37114240.000-37115256.000) | 37114696.000 (37114376.000-37115288.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| unqueued | shared-queue -> per-function | steady p99 | window | 4.401 (3.903-5.117) | 4.109 (3.957-4.276) | -6.62 | 5 | PASS | overlaps | -6.62 |
| unqueued | shared-queue -> per-function | steady useful throughput | window | 300.000 (275.500-300.500) | 299.000 (272.500-300.000) | -0.33 | 5 | PASS | overlaps | -0.33 |
| unqueued | shared-queue -> per-function | thread cpu per useful completion | whole run | 100189.000 (90265.000-111081.000) | 92198.000 (84362.000-100116.000) | -7.98 | 10 | PASS | overlaps | — (no steady form) |
| unqueued | shared-queue -> per-function | post-GC heap | whole run | 37114560.000 (37114360.000-37115416.000) | 37114648.000 (37114552.000-37114928.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |

## Resolving power: the smallest consistent regression this test could have caught

Each cell is how many eligible comparisons would read `disjoint` if the switched arm's true value were exactly (1 + factor) times its control's, arm by arm — a uniform multiplicative effect, the easiest possible case to detect, and one the test still has to beat. A budget well below the row's first detectable factor cannot have been adjudicated by this campaign.

| metric | budget % | eligible | +5 % | +10 % | +20 % | +30 % | +50 % | +100 % | median arm spread % |
|---|---|---|---|---|---|---|---|---|---|
| steady p99 | 5 | 22 | 6 | 7 | 9 | 13 | 18 | 22 | 16.1 |
| steady useful throughput | 5 | 22 | 7 | 16 | 20 | 20 | 22 | 22 | 7.1 |
| thread cpu per useful completion | 10 | 24 | 0 | 0 | 4 | 11 | 22 | 24 | 26.9 |
| post-GC heap | 10 | 24 | 24 | 24 | 24 | 24 | 24 | 24 | 0.0 |

The arms' own spread is the reason, and it is two to three times the p99 and CPU budgets: a uniform 5 % effect is invisible to a test whose arms disagree by 16 % between repetitions.

## What the round-1 pairing defect is worth, recomputed from this artifact

The largest wrong-pairing delta in this campaign is on **head-of-line-blocking**: **+3805.3 %**, purely from comparing each switched arm against the strategy it started on rather than the one it ended on. The same comparisons, paired correctly:

| workload | switched arm | compared against | steady p99 control ms | switched ms | delta % |
|---|---|---|---|---|---|
| head-of-line-blocking | per-function -> shared-queue | wrong (round 1): per-function (no change) | 3.133 | 122.368 | +3805.3 |
| head-of-line-blocking | shared-queue -> per-function | wrong (round 1): shared-queue (no change) | 122.947 | 2.997 | -97.6 |
| head-of-line-blocking | per-function -> shared-queue | right (round 3): shared-queue (no change) | 122.947 | 122.368 | -0.5 |
| head-of-line-blocking | shared-queue -> per-function | right (round 3): per-function (no change) | 3.133 | 2.997 | -4.4 |

Reproduce with `python3 summarize.py raw/steady.jsonl`. Round 1's own +3851 % cannot be reproduced: its artifact was overwritten by the corrected run before it was committed, which is itself the reason this file now computes the figure instead of quoting one.
## Round 1 and 2's tables

These are the earlier protocol's figures. Round 1's regression table is **superseded** for the p99 and CPU budgets by the steady table above and is kept because it is the artifact the protocol defect is visible in, and because its per-workload medians are still the record of the workloads themselves.

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

## Switched arm vs the same strategy's no-change arm (median of 5 repetitions)

A delta over budget is a miss. `dispersion` says whether the arms are separated by this measurement at all: arms whose 5-repetition ranges overlap are not, and the brief's own criterion for that case is to declare the result not distinguishable rather than to read the delta as an effect. That phrase describes the **instrument**, not the code — for p99 and CPU the ranges are wide enough that a consistent effect of the budget's own size would not separate them, which the resolving-power table below quantifies. This is the table the summary above calls **superseded** for those two metrics.

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

**Re-run against the committed harness** (`raw/baseline.jsonl`, harness digest
`d42cd7f79c1cba04…`, revision `3f352ee4`), which is the artifact the plan requires for
this check:

| figure | switched phase | no-switch control | delta |
|---|---|---|---|
| switches requested / committed / refused | 1000 / 1000 / 0 | — | — |
| pending at end | 398 | 398 | +0 (+0.00 %) |
| largest per-function reservation difference | — | — | 0 (+0.00 %) |
| live indexes at end | 1 | 1 | +0 |
| max live indexes during the run | 2 | — | — |
| indexes created / cleared | 1001 / 1000 | — | — |
| admitted | 4149 | 4117 | +32 |
| completed | 3749 | 3717 | +32 |
| work conserved (admitted == closure) | True | True | — |
| post-GC heap MB | 37.31 | 37.22 | +0.23 % |
| switch pause p50 / p99 / max ms | 0.114 / 0.347 / 4.714 | — | — |

Pending and the per-function reservations return to the control's values **exactly** (delta 0, and 0
tickets respectively), which is tighter than the round-1 measurement of the same check (399 against
400, largest reservation difference 1). The round-1 table below is kept because it is the reading
recorded during the fix rounds, with its round-1 harness.

### The round-1 measurement of the same check

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
| 7. payloads of different sizes, many functions, churn and traffic stop: memory, indexes, drain | `churn-drain` | 20 functions, 4 KiB vs 64 B payloads, churn at 25 % of the span (half the sporadic functions removed and re-registered), offering stopped at 60 % of the span — 4.8 s of an 8 000 ms span — so the queue drains for the rest of it | — |
| 8. capacity changes under load: progress and protocol correctness | `capacity-change` | effective concurrency **reduced** 2 → 1 at 25 % of the span, restored at 60 % — the direction the registry's API can actually make (`setEffectiveConcurrency` clamps an increase to the configured value, which is the defect this round fixed), with the effective value read back into the artifact | — |
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
- Workload event offsets (`burstAtPercent`, `churnAtPercent`, `capacityChangeAtPercent`,
  `stopAtPercent`) are **percentages of the measured span**, not absolute milliseconds, so a longer
  span still contains the burst, the churn and the capacity change at the same point of the arm's
  own history. `capacity-change` and `churn-drain` use 25 %, `heterogeneous-burst` 12 % and
  `switch-under-load` 12 %, `churn-drain` stops offering at 60 %.
- The two campaigns ran different spans: round 1 measured 2 500 ms after a 1 500 ms warm-up, the
  settlement campaign 8 000 ms after the same warm-up, so the trailing-window grid exists and the
  settling can be segmented. Round-1 figures and round-3 figures are labelled by artifact
  throughout; nothing silently mixes them.
- `budgets.json`'s `repetitions: 5` is used for every arm and every workload. No arm or workload
  ran fewer.
- **No 60-minute soak was run.** The plan assigns that to Task 13 (plan line 837, against Task
  13's frozen artifact). `switchesInSoak: 1000` is used here for what this task's brief asks: the
  switch *count* and the return-to-baseline check after it.

## Limitations of these numbers

- **The p99 figures are censored by the contract deadline, and this is not incidental.** The engine
  expires a queued ticket at its deadline, so under load queueing turns into *expiry* rather than
  into latency: a ticket that waits too long never completes and therefore never appears in a
  latency percentile at all. The 4 loaded p99 comparisons sit close to their cap for exactly this
  reason (`saturated`: 61.4 ms against a 60 ms contract) and are structurally insensitive to it —
  the metric that carries those workloads is useful throughput, which is the spec's own point
  (*«il dispatch rate da solo non basta»*). A reader should read a flat p99 under load as
  "censored", not as "healthy".
- **The async deadline this harness applies is stricter than the one that ships.** In production the
  async front passes `queueDeadline = null`
  (`platform/control-plane/.../service/EngineInvocationEnqueuer.java:137`) and only the sync front
  sets `now + syncQueueMaxQueueWait`
  (`platform/control-plane/.../service/EngineSyncQueueGateway.java:206`). This harness stamps
  `now.plusMillis(profile.contractMs)` on **every** ticket regardless of kind
  (`SchedulerSwitchBenchmark.java`), including ASYNC, because the contract deadline is the quantity
  that gives "useful throughput" its meaning. So every async expiry figure and every p99 that
  depends on it in this file describes a **stricter policy than production** and is an upper bound
  on it, not a prediction of it.
- **The settlement campaign ran the workload profiles only.** The backlog sweep and the
  1 000-switch phase are round 1's and were not re-run, on instruction; their figures stand on
  `raw/full.jsonl`.
- **`churn-drain` has no steady figure.** It stops offering at 60 % of its span by design, so the
  trailing 2 000 ms holds no arrivals and there is nothing to measure a steady p99 over. Its two
  comparisons are reported as not measurable, not as passes, and its whole-span figures are the
  ones to read.
- **`capacity-change` was measured on a workload that was not what it claimed to be** in rounds 1
  and 2 (the capacity change was silently clamped away). Round 3's version makes a real change and
  proves it in the artifact; the earlier row is superseded.
- **The settling rule contains three constants** — a 500 ms smoothing window, Tukey's 1.5 IQR, and
  a 2 000 ms steady window — fixed before the campaign and applied to every arm and workload. They
  are not tuned per workload, and the whole trailing-window grid is in `raw/steady.jsonl` so any
  other choice can be read off the artifact instead of requiring a re-run.

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
| `raw/full.jsonl` (+`raw/full.err`) | `./run.sh --label=full` | the campaign: pause sweep, all twelve workloads × four arms × five repetitions, and the 1000-switch return-to-baseline phase. 260 `sample` lines, 140 `switch` lines, one `baseline` line, one `summary` line, one `header` line |
| `raw/smoke.jsonl` (+`raw/smoke.err`) | `./run.sh --label=smoke --parts=backlog --backlogs=100 --repetitions=1` | the end-to-end proof that the harness works: one backlog size, one repetition |
| `raw/steady.err` (note) | — | `git diff --check` over this task's whole range reports **96 lines of output, which are 48 flagged lines** (`git diff --check` prints each offender twice: the complaint and the line itself, the latter with the leading `+` its own output adds). All 48 are in this file: two capacity read-back messages — a trailing space from the diagnostic's own formatting — once per run of the `capacity-change` workload, 24 runs each. They are **left as captured**, deliberately: the artifact has to keep matching the `harnessSha256` in its own header, and editing captured evidence to satisfy a linter is the wrong trade. The check was previously reported as clean, which was wrong — it had been run on the unstaged diff only. A `new blank line at EOF` in `RESULTS.md` was also flagged and is fixed, because a document is not captured evidence |
| `raw/baseline.jsonl` (+`raw/baseline.err`) | `./run.sh --label=baseline --parts=switches` | the 1 000-switch return-to-baseline phase re-run against the committed harness (digest in its own header), with its no-switch control phase |
| `raw/steady.jsonl` (+`raw/steady.err`) | `./run.sh --label=steady --parts=profiles` | the settlement campaign: all twelve workloads × four arms × five repetitions on an 8 000 ms span, with the queue-depth trajectory and the trailing-window grid. 240 `sample` lines, 120 `switch` lines |
| `raw/diagnostic-hol-window-1200.jsonl`, `…-4000.jsonl`, `…-10000.jsonl` | `./run.sh --label=win<N> --parts=profiles --profiles=head-of-line-blocking --repetitions=3 --window-ms=<N>` (renamed after the run) | the window-length diagnostic on the head-of-line-blocking tail |
| `raw/diagnostic-cc-window-4000.jsonl`, `…-10000.jsonl` | `./run.sh --label=diagcc<N> --parts=profiles --profiles=capacity-change --repetitions=3 --window-ms=<N>` (renamed after the run) | the window-length diagnostic that first characterised the round-1 capacity-change miss. Superseded by round 3, which found the workload itself was mis-specified — the clamp defect below |
| `raw/clock-resolution.txt` | `javac ClockTest.java && java ClockTest` (source: `ClockTest.java` in this directory) | the CPU-clock resolution measurement the CPU budget's adjudication rests on |
| `raw/host-quietness.txt` | written by hand from the `/proc/loadavg` samples beside it, plus the readings either side of each campaign | what was checked about the host, and the coverage limit of that sampling |
| `raw/load-average-samples-steady.txt`, `raw/load-average-samples-round1-tail.txt` | `/proc/loadavg` sampled every 45 s by an independent loop, committed verbatim | the load data the quietness claims were read from. The round-1 file covers the diagnostics and the tail of the run before the round-1 campaign, **not** that campaign — the limit is stated in `host-quietness.txt` |

**Which harness produced which artifact** — read from each artifact's own header, not asserted:

| harness digest | artifacts carrying it |
|---|---|
| `831828d6…` | `raw/steady.jsonl` |
| `d42cd7f7…` | `raw/baseline.jsonl` |
| `f1941ab4…` | `raw/diagnostic-cc-window-10000.jsonl`, `raw/diagnostic-cc-window-4000.jsonl`, `raw/diagnostic-hol-window-10000.jsonl`, `raw/diagnostic-hol-window-1200.jsonl`, `raw/diagnostic-hol-window-4000.jsonl`, `raw/full.jsonl`, `raw/smoke.jsonl` |

Three builds, and what separates them:

- **`f1941ab4…`** — the round-1/2 harness. It is the one that produced the mechanism verdicts
  (`raw/full.jsonl`: pause, preparation, index count, switch count), the smoke run, and the six
  window-length diagnostics. The mechanism rows are not affected by the later revisions — a 60×
  margin on the pause cannot be moved by a diagnostic-formatting change — but they were measured by
  an earlier build and that is stated here rather than left implicit. The **window-length
  diagnostics** are a different matter: they were taken on the round-1 protocol and are cited in
  this file only as the history of how the settling question arose, not as evidence for any verdict
  in the tables above. The corrected protocol's evidence is `raw/steady.jsonl`.
- **`831828d6…`** — the round-3 harness, committed at `5ecd6def`: the settlement protocol (span,
  depth trajectory, trailing-window grid, thread CPU, the real capacity change, the switch at the
  span's start). It produced `raw/steady.jsonl`.
- **`d42cd7f7…`** — the current harness, committed at `0062ab2a`. The only change from `831828d6` is
  a comment block — `git diff 5ecd6def -- …/SchedulerSwitchBenchmark.java` shows four comment lines
  removed and nine added, with no executable statement touched — which is why `raw/baseline.jsonl`
  carries this digest while measuring the same thing as the campaign it is reported beside.

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

## Workload coverage table notes

The per-workload table's `useful/s` is the useful throughput — completions inside the contract
deadline, per second of measured window. `p99` is the end-to-end latency (admission to
completion) at the 99th percentile over the window, for samples admitted inside the window.
`cpu/useful` is **thread** CPU time divided by useful completions (the sum of every live thread's CPU time; the process clock is quantised to 10 ms here and cannot resolve a 10 % budget — see the CPU-resolution section). `alloc/useful` is total allocated
bytes (all threads, harness included) divided by useful completions. `post-GC heap` is the live
heap after three forced collections. `pending` is the queue population at the moment the worker
was stopped. `conserved` is the accounting identity below.
