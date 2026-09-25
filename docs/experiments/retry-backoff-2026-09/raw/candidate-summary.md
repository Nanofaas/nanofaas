# Scheduler-switching benchmark — summary of `retry-backoff-candidate-steady.jsonl`

- sha `a17289c2e974e280c1f299e2f871f034c0c632aa`, artifact `jvm`, OpenJDK 64-Bit Server VM 25.0.4+7-1-24.04-Ubuntu, Linux 6.17.0-1032-nvidia aarch64, 20 CPUs, max heap 1.00 GiB
- harness `SchedulerSwitchBenchmark.java` sha256 `cf5cdc258a38abb8344b3fcfaa31d7fcf52f0645d61592a0b182b2aa2642e56a`
- budgets read from `/home/michele/Documenti/nanofaas/docs/experiments/scheduler-switching-2026-09/budgets.json`: `{"repetitions": 5, "switchBacklogSizes": [0, 100, 1000, 10000], "maxSwitchPauseMs": 250, "maxSwitchPauseP99Ms": 100, "maxSwitchPreparationMs": 2000, "maxLiveStrategyIndexes": 2, "switchesInSoak": 1000}`

## Does the queue converge? — the settling rule, read off the depth trajectory

`settledDepth` is the median of the final quarter of the trajectory; `settleMillis` is the last point of the span still outside ±25 % of it. Both are backlog-only figures — the segmentation cannot be steered by the latency it segments. The steady window is the trailing 2000 ms, valid where every arm settled before it opens.

| workload | arm | settled depth | settled Q1-Q3 | settle ms (worst rep) | settled before the steady window |
|---|---|---|---|---|---|
| low-load | per-function (no change) | 0 | -2-2 | 0 | yes |
| low-load | shared-queue (no change) | 0 | -2-2 | 0 | yes |
| low-load | per-function -> shared-queue | 0 | -2-2 | 0 | yes |
| low-load | shared-queue -> per-function | 0 | -2-2 | 0 | yes |

### Are the switched arms comparable to their control once settled?

| workload | switched arm | target control | settled depth switched (Q1-Q3) | control (Q1-Q3) | delta | verdict |
|---|---|---|---|---|---|---|
| low-load | per-function -> shared-queue | shared-queue (no change) | 0 (-2-2) | 0 (-2-2) | +0 tickets | converged |
| low-load | shared-queue -> per-function | per-function (no change) | 0 (-2-2) | 0 (-2-2) | +0 tickets | converged |

## The budgets on the corrected protocol, eligibility tested per metric

`window` metrics are read off the trailing 2000 ms and are eligible only where both arms settled before that window opened (the settling table above) *and* the window holds arrivals. `whole run` metrics — CPU per useful completion, which is span CPU over span completions, and post-GC heap, measured after the run — have no steady form, so they have one column and no transient-inclusive counterpart, and a workload that stops its traffic is not an obstacle to them and is not exempted from them.

| workload | switch | metric | nature | control | switched | delta % | budget % | verdict | dispersion | transient-inclusive delta % |
|---|---|---|---|---|---|---|---|---|---|---|
| low-load | per-function -> shared-queue | steady p99 | window | 2.726 (2.520-3.406) | 2.576 (2.549-3.296) | -5.52 | 5 | PASS | overlaps | -2.83 |
| low-load | per-function -> shared-queue | steady useful throughput | window | 21.500 (16.500-24.500) | 21.500 (16.500-24.500) | +0.00 | 5 | PASS | overlaps | +0.02 |
| low-load | per-function -> shared-queue | thread cpu per useful completion | whole run | 385133.000 (342716.000-414430.000) | 385542.000 (359927.000-424183.000) | +0.11 | 10 | PASS | overlaps | — (no steady form) |
| low-load | per-function -> shared-queue | post-GC heap | whole run | 37161144.000 (37160992.000-37161184.000) | 37161200.000 (37161104.000-37161272.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| low-load | shared-queue -> per-function | steady p99 | window | 2.757 (2.420-3.738) | 2.603 (2.472-3.405) | -5.61 | 5 | PASS | overlaps | +3.22 |
| low-load | shared-queue -> per-function | steady useful throughput | window | 21.500 (16.500-24.500) | 21.500 (16.500-24.500) | +0.00 | 5 | PASS | overlaps | -0.00 |
| low-load | shared-queue -> per-function | thread cpu per useful completion | whole run | 405760.000 (346296.000-476999.000) | 410404.000 (355676.000-449418.000) | +1.14 | 10 | PASS | overlaps | — (no steady form) |
| low-load | shared-queue -> per-function | post-GC heap | whole run | 37161184.000 (37150136.000-37161280.000) | 37161312.000 (37161248.000-37161408.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |

## Resolving power: the smallest consistent regression this test could have caught

The operation is deterministic and reads one arm: each control arm's own five values are multiplied by (1 + factor) and tested for separation from that same control's range. It never reads the switched arm's data, so the table is a restatement of the control arms' own spread — how much a perfectly uniform shift would have to be before this test could see it at all — and not an independent power calculation over the observed effect. It is the more conservative reading for that reason: a real effect carries noise the shifted arm does not have. A budget below the row's first detectable factor could not have been adjudicated by this campaign.

| metric | budget % | eligible | +5 % | +10 % | +20 % | +30 % | +50 % | +100 % | median arm spread % |
|---|---|---|---|---|---|---|---|---|---|
| steady p99 | 5 | 2 | 0 | 0 | 0 | 0 | 1 | 2 | 32.8 |
| steady useful throughput | 5 | 2 | 0 | 0 | 0 | 0 | 2 | 2 | 37.2 |
| thread cpu per useful completion | 10 | 2 | 0 | 0 | 0 | 1 | 2 | 2 | 21.0 |
| post-GC heap | 10 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 0.0 |

The arms' own spread is the reason, and it is two to three times the p99 and CPU budgets: a uniform 5 % effect is invisible to a test whose arms disagree by 16 % between repetitions.

## What the round-1 pairing defect is worth, recomputed from this artifact

The largest wrong-pairing delta in this campaign is on **low-load**: **-6.6 %**, purely from comparing each switched arm against the strategy it started on rather than the one it ended on. The same comparisons, paired correctly:

| workload | switched arm | compared against | steady p99 control ms | switched ms | delta % |
|---|---|---|---|---|---|
| low-load | per-function -> shared-queue | wrong (round 1): per-function (no change) | 2.757 | 2.576 | -6.6 |
| low-load | shared-queue -> per-function | wrong (round 1): shared-queue (no change) | 2.726 | 2.603 | -4.5 |
| low-load | per-function -> shared-queue | right (round 3): shared-queue (no change) | 2.726 | 2.576 | -5.5 |
| low-load | shared-queue -> per-function | right (round 3): per-function (no change) | 2.757 | 2.603 | -5.6 |

Reproduce with `python3 summarize.py raw/steady.jsonl`. Round 1's own +3851 % cannot be reproduced: its artifact was overwritten by the corrected run before it was committed, which is itself the reason this file now computes the figure instead of quoting one.

## Frozen budgets: observed against thresholds

| budget (budgets.json) | frozen | observed | source | verdict |
|---|---|---|---|---|
| maxSwitchPauseMs | 250 ms | 3.584 ms | max engine pause over every measured switch (sweep + workloads + 1000-switch run) (summary.pauseMaxMs) | PASS |
| maxSwitchPauseP99Ms | 100 ms | 0.265 ms | p99 over the 1000-switch run (summary.soakPauseP99Ms) | PASS |
| maxSwitchPreparationMs | 2000 ms | 3.584 ms | total switch duration, an upper bound on the preparation phase (summary.pauseMaxMs) | PASS |
| maxLiveStrategyIndexes | 2  | 2 | max live indexes observed (samples, switch events, baseline) | PASS |
| switchesInSoak | 1000  | 1000 committed / 0 refused | baseline line | PASS |
| maxSteadyP99RegressionPercent | 5 % | -5.52 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxUsefulThroughputRegressionPercent | 5 % | +0.00 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxCpuPerCompletionRegressionPercent | 10 % | +1.14 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxPostGcHeapRegressionPercent | 10 % | +0.00 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| repetitions | 5  | [5] measured samples per (workload, arm) | counted over every workload sample in the artifact | PASS |
| switchBacklogSizes | [0, 100, 1000, 10000]  | [0, 100, 1000, 10000] | sizes swept, each with 0/5 reps, 100/5 reps, 1000/5 reps, 10000/5 reps | PASS |

Client-side corroboration, never substituted for the engine's figure: worst client elapsed 3.606 ms (budgets give no client figure).

## Pause sweep (budgets.json switchBacklogSizes)

| requested backlog | switches | pending at switch | pause p50 ms | pause max ms | client max ms | live indexes max | outcome |
|---|---|---|---|---|---|---|---|
| 0 | 5 | 0-0 | 0.111 | 0.130 | 1.296 | 1 | COMMITTED |
| 100 | 5 | 98-99 | 0.209 | 0.232 | 0.243 | 1 | COMMITTED |
| 1000 | 5 | 999-1000 | 0.591 | 0.666 | 0.687 | 1 | COMMITTED |
| 10000 | 5 | 9999-10000 | 2.471 | 3.584 | 3.606 | 1 | COMMITTED |

## Per-workload medians over the 5 repetitions (min-max in brackets)

| workload | arm | reps | useful/s | p99 ms | thread cpu/useful us | alloc/useful B | post-GC heap MB | pending | conserved |
|---|---|---|---|---|---|---|---|---|---|
| low-load | per-function (no change) | 5 | 24.2 (21.7-26.5) | 2.641 | 405.76 | 1206 | 37.16 | 0 | True |
| low-load | per-function -> shared-queue | 5 | 24.2 (21.7-26.5) | 2.677 | 385.54 | 1006 | 37.16 | 0 | True |
| low-load | shared-queue (no change) | 5 | 24.2 (21.7-26.5) | 2.755 | 385.13 | 1030 | 37.16 | 0 | True |
| low-load | shared-queue -> per-function | 5 | 24.2 (21.7-26.5) | 2.726 | 410.40 | 1215 | 37.16 | 0 | True |

## Return to baseline after 1000 switches, against a no-switch control phase

| figure | switched phase | no-switch control | delta |
|---|---|---|---|
| pending at end | 403 | 397 | +6 (+1.51 %) |
| largest per-function reservation difference | - | - | 6 (+1.51 %) |
| live indexes at end | 1 | 1 | +0 |
| max live indexes during the run | 2 | - | - |
| indexes created / cleared | 1001 / 1000 | - | - |
| admitted | 4272 | 4299 | -0.63 % |
| completed | 3867 | 3899 | -0.82 % |
| useful | 3867 | 3899 | -0.82 % |
| work conserved (admitted == closure) | True | True | - |
| post-GC heap | 37.54 MB | 37.45 MB | +0.24 % |
| switch pause p50 / p99 / max | 0.095 / 0.265 / 0.450 ms | - | - |

### Work conservation across every measured run

- workload runs that conserve work (admitted == completed + expired + removed + rejected + pending + claimed + submitting + in flight): **20 of 20**
- runs with a driver failure: **0**
- runs whose sample cap was reached: **0**
- switch events emitted: **32**
