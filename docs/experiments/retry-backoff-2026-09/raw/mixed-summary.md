# Scheduler-switching benchmark — summary of `retry-backoff-candidate-mixed.jsonl`

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
| maxSwitchPauseMs | 250 ms | 5.469 ms | max engine pause over every measured switch (sweep + workloads + 1000-switch run) (summary.pauseMaxMs) | PASS |
| maxSwitchPauseP99Ms | 100 ms | 0.315 ms | p99 over the 1000-switch run (summary.soakPauseP99Ms) | PASS |
| maxSwitchPreparationMs | 2000 ms | 5.469 ms | total switch duration, an upper bound on the preparation phase (summary.pauseMaxMs) | PASS |
| maxLiveStrategyIndexes | 2  | 2 | max live indexes observed (samples, switch events, baseline) | PASS |
| switchesInSoak | 1000  | 1000 committed / 0 refused | baseline line | PASS |
| maxSteadyP99RegressionPercent | 5 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| maxUsefulThroughputRegressionPercent | 5 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| maxCpuPerCompletionRegressionPercent | 10 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| maxPostGcHeapRegressionPercent | 10 % | not measured | worst switched-vs-no-change delta (see the regression table) | NO DATA |
| repetitions | 5  | [] measured samples per (workload, arm) | counted over every workload sample in the artifact | MISS |
| switchBacklogSizes | [0, 100, 1000, 10000]  | [0, 100, 1000, 10000] | sizes swept, each with 0/5 reps, 100/5 reps, 1000/5 reps, 10000/5 reps | PASS |

Client-side corroboration, never substituted for the engine's figure: worst client elapsed 5.491 ms (budgets give no client figure).

## Pause sweep (budgets.json switchBacklogSizes)

| requested backlog | switches | pending at switch | pause p50 ms | pause max ms | client max ms | live indexes max | outcome |
|---|---|---|---|---|---|---|---|
| 0 | 5 | 0-0 | 0.119 | 0.153 | 0.803 | 1 | COMMITTED |
| 100 | 5 | 100-100 | 0.205 | 0.262 | 0.280 | 1 | COMMITTED |
| 1000 | 5 | 999-999 | 0.508 | 0.762 | 0.780 | 1 | COMMITTED |
| 10000 | 5 | 9999-10000 | 3.044 | 5.469 | 5.491 | 1 | COMMITTED |

## Per-workload medians over the 5 repetitions (min-max in brackets)

| workload | arm | reps | useful/s | p99 ms | cpu/useful us (mixed clocks) | alloc/useful B | post-GC heap MB | pending | conserved |
|---|---|---|---|---|---|---|---|---|---|

## Return to baseline after 1000 switches, against a no-switch control phase

| figure | switched phase | no-switch control | delta |
|---|---|---|---|
| pending at end | 395 | 398 | -3 (-0.75 %) |
| largest per-function reservation difference | - | - | 10 (+47.62 %) |
| live indexes at end | 1 | 1 | +0 |
| max live indexes during the run | 2 | - | - |
| indexes created / cleared | 1001 / 1000 | - | - |
| admitted | 3959 | 3959 | +0.00 % |
| completed | 3557 | 3558 | -0.03 % |
| useful | 3557 | 3558 | -0.03 % |
| work conserved (admitted == closure) | True | True | - |
| post-GC heap | 37.52 MB | 37.44 MB | +0.22 % |
| switch pause p50 / p99 / max | 0.126 / 0.315 / 0.584 ms | - | - |

### Work conservation across every measured run

- workload runs that conserve work (admitted == completed + expired + removed + rejected + pending + claimed + submitting + in flight): **0 of 0**
- runs with a driver failure: **0**
- runs whose sample cap was reached: **0**
- switch events emitted: **20**
