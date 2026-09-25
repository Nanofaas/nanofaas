# Scheduler-switching benchmark — summary of `retry-backoff-candidate-delayed.jsonl`

- sha `a17289c2e974e280c1f299e2f871f034c0c632aa`, artifact `jvm`, OpenJDK 64-Bit Server VM 25.0.4+7-1-24.04-Ubuntu, Linux 6.17.0-1032-nvidia aarch64, 20 CPUs, max heap 1.00 GiB
- harness `SchedulerSwitchBenchmark.java` sha256 `cf5cdc258a38abb8344b3fcfaa31d7fcf52f0645d61592a0b182b2aa2642e56a`
- budgets read from `/home/michele/Documenti/nanofaas/docs/experiments/scheduler-switching-2026-09/budgets.json`: `{"repetitions": 5, "switchBacklogSizes": [0, 100, 1000, 10000], "maxSwitchPauseMs": 250, "maxSwitchPauseP99Ms": 100, "maxSwitchPreparationMs": 2000, "maxLiveStrategyIndexes": 2, "switchesInSoak": 1000}`

## This artifact predates the settling protocol

It carries neither a depth trajectory nor a trailing-window grid, so the steady tables cannot be derived from it. The table below is the protocol it *was* measured under — the switched arm against the strategy it started on, over the whole window — which is the artifact the mid-window mixture defect is visible in.

## Switched arm vs the same strategy's no-change arm (median of 5 repetitions)

A delta over budget is a miss. `dispersion` says whether it is *separable*: arms whose 5-repetition ranges overlap are not separated by this measurement, and the brief's own criterion for that case is to declare the result not distinguishable rather than to read the delta as an effect.

| workload | switch | metric | no-change (5-rep range) | switched (5-rep range) | delta % | budget % | verdict | dispersion |
|---|---|---|---|---|---|---|---|---|

## Frozen budgets: observed against thresholds

| budget (budgets.json) | frozen | observed | source | verdict |
|---|---|---|---|---|
| maxSwitchPauseMs | 250 ms | 5.591 ms | max engine pause over every measured switch (sweep + workloads + 1000-switch run) (summary.pauseMaxMs) | PASS |
| maxSwitchPauseP99Ms | 100 ms | 0.395 ms | p99 over the 1000-switch run (summary.soakPauseP99Ms) | PASS |
| maxSwitchPreparationMs | 2000 ms | 5.591 ms | total switch duration, an upper bound on the preparation phase (summary.pauseMaxMs) | PASS |
| maxLiveStrategyIndexes | 2  | 2 | max live indexes observed (samples, switch events, baseline) | PASS |
| switchesInSoak | 1000  | 1000 committed / 0 refused | baseline line | PASS |
| maxSteadyP99RegressionPercent | 5 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| maxUsefulThroughputRegressionPercent | 5 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| maxCpuPerCompletionRegressionPercent | 10 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| maxPostGcHeapRegressionPercent | 10 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| repetitions | 5  | [] measured samples per (workload, arm) | counted over every workload sample in the artifact | MISS |
| switchBacklogSizes | [0, 100, 1000, 10000]  | [0, 100, 1000, 10000] | sizes swept, each with 0/5 reps, 100/5 reps, 1000/5 reps, 10000/5 reps | PASS |

Client-side corroboration, never substituted for the engine's figure: worst client elapsed 5.614 ms (budgets give no client figure).

## Pause sweep (budgets.json switchBacklogSizes)

| requested backlog | switches | pending at switch | pause p50 ms | pause max ms | client max ms | live indexes max | outcome |
|---|---|---|---|---|---|---|---|
| 0 | 5 | 0-0 | 0.122 | 0.137 | 0.766 | 1 | COMMITTED |
| 100 | 5 | 100-100 | 0.201 | 0.238 | 0.256 | 1 | COMMITTED |
| 1000 | 5 | 1000-1000 | 0.540 | 0.667 | 0.688 | 1 | COMMITTED |
| 10000 | 5 | 10000-10000 | 3.027 | 5.591 | 5.614 | 1 | COMMITTED |

## Per-workload medians over the 5 repetitions (min-max in brackets)

| workload | arm | reps | useful/s | p99 ms | cpu/useful us (mixed clocks) | alloc/useful B | post-GC heap MB | pending | conserved |
|---|---|---|---|---|---|---|---|---|---|

## Return to baseline after 1000 switches, against a no-switch control phase

| figure | switched phase | no-switch control | delta |
|---|---|---|---|
| pending at end | 457 | 496 | -39 (-7.86 %) |
| largest per-function reservation difference | - | - | 16 (+40.74 %) |
| live indexes at end | 1 | 1 | +0 |
| max live indexes during the run | 2 | - | - |
| indexes created / cleared | 1001 / 1000 | - | - |
| admitted | 2728 | 2715 | +0.48 % |
| completed | 2261 | 2217 | +1.98 % |
| useful | 2261 | 2217 | +1.98 % |
| work conserved (admitted == closure) | True | True | - |
| post-GC heap | 37.57 MB | 37.51 MB | +0.17 % |
| switch pause p50 / p99 / max | 0.141 / 0.395 / 0.676 ms | - | - |

### Work conservation across every measured run

- workload runs that conserve work (admitted == completed + expired + removed + rejected + pending + claimed + submitting + in flight): **0 of 0**
- runs with a driver failure: **0**
- runs whose sample cap was reached: **0**
- switch events emitted: **20**
