# Scheduler-switching benchmark — summary of `retry-backoff-baseline-steady.jsonl`

- sha `a17289c2e974e280c1f299e2f871f034c0c632aa`, artifact `jvm`, OpenJDK 64-Bit Server VM 25.0.4+7-1-24.04-Ubuntu, Linux 6.17.0-1032-nvidia aarch64, 20 CPUs, max heap 1.00 GiB
- harness `SchedulerSwitchBenchmark.java` sha256 `cf5cdc258a38abb8344b3fcfaa31d7fcf52f0645d61592a0b182b2aa2642e56a`
- budgets read from `/tmp/retry-backoff-baseline-a17289c2/docs/experiments/scheduler-switching-2026-09/budgets.json`: `{"repetitions": 5, "switchBacklogSizes": [0, 100, 1000, 10000], "maxSwitchPauseMs": 250, "maxSwitchPauseP99Ms": 100, "maxSwitchPreparationMs": 2000, "maxLiveStrategyIndexes": 2, "switchesInSoak": 1000}`

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
| low-load | per-function -> shared-queue | steady p99 | window | 2.571 (2.548-3.330) | 2.671 (2.554-3.399) | +3.87 | 5 | PASS | overlaps | +0.27 |
| low-load | per-function -> shared-queue | steady useful throughput | window | 21.500 (16.500-24.500) | 21.500 (16.500-24.500) | +0.00 | 5 | PASS | overlaps | +0.02 |
| low-load | per-function -> shared-queue | thread cpu per useful completion | whole run | 387333.000 (351053.000-433114.000) | 406705.000 (329740.000-439619.000) | +5.00 | 10 | PASS | overlaps | — (no steady form) |
| low-load | per-function -> shared-queue | post-GC heap | whole run | 37156888.000 (37156704.000-37156920.000) | 37156936.000 (37156840.000-37157008.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |
| low-load | shared-queue -> per-function | steady p99 | window | 2.809 (2.483-3.311) | 2.679 (2.214-3.419) | -4.64 | 5 | PASS | overlaps | -1.74 |
| low-load | shared-queue -> per-function | steady useful throughput | window | 21.500 (16.500-24.500) | 21.500 (16.500-24.500) | +0.00 | 5 | PASS | overlaps | -0.01 |
| low-load | shared-queue -> per-function | thread cpu per useful completion | whole run | 368851.000 (346366.000-442202.000) | 370204.000 (303387.000-474681.000) | +0.37 | 10 | PASS | overlaps | — (no steady form) |
| low-load | shared-queue -> per-function | post-GC heap | whole run | 37156944.000 (37145880.000-37157040.000) | 37157072.000 (37157008.000-37157168.000) | +0.00 | 10 | PASS | overlaps | — (no steady form) |

## Resolving power: the smallest consistent regression this test could have caught

The operation is deterministic and reads one arm: each control arm's own five values are multiplied by (1 + factor) and tested for separation from that same control's range. It never reads the switched arm's data, so the table is a restatement of the control arms' own spread — how much a perfectly uniform shift would have to be before this test could see it at all — and not an independent power calculation over the observed effect. It is the more conservative reading for that reason: a real effect carries noise the shifted arm does not have. A budget below the row's first detectable factor could not have been adjudicated by this campaign.

| metric | budget % | eligible | +5 % | +10 % | +20 % | +30 % | +50 % | +100 % | median arm spread % |
|---|---|---|---|---|---|---|---|---|---|
| steady p99 | 5 | 2 | 0 | 0 | 0 | 0 | 2 | 2 | 29.7 |
| steady useful throughput | 5 | 2 | 0 | 0 | 0 | 0 | 2 | 2 | 37.2 |
| thread cpu per useful completion | 10 | 2 | 0 | 0 | 0 | 2 | 2 | 2 | 26.3 |
| post-GC heap | 10 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 0.0 |

The arms' own spread is the reason, and it is two to three times the p99 and CPU budgets: a uniform 5 % effect is invisible to a test whose arms disagree by 16 % between repetitions.

## What the round-1 pairing defect is worth, recomputed from this artifact

The largest wrong-pairing delta in this campaign is on **low-load**: **-4.9 %**, purely from comparing each switched arm against the strategy it started on rather than the one it ended on. The same comparisons, paired correctly:

| workload | switched arm | compared against | steady p99 control ms | switched ms | delta % |
|---|---|---|---|---|---|
| low-load | per-function -> shared-queue | wrong (round 1): per-function (no change) | 2.809 | 2.671 | -4.9 |
| low-load | shared-queue -> per-function | wrong (round 1): shared-queue (no change) | 2.571 | 2.679 | +4.2 |
| low-load | per-function -> shared-queue | right (round 3): shared-queue (no change) | 2.571 | 2.671 | +3.9 |
| low-load | shared-queue -> per-function | right (round 3): per-function (no change) | 2.809 | 2.679 | -4.6 |

Reproduce with `python3 summarize.py raw/steady.jsonl`. Round 1's own +3851 % cannot be reproduced: its artifact was overwritten by the corrected run before it was committed, which is itself the reason this file now computes the figure instead of quoting one.

## Frozen budgets: observed against thresholds

| budget (budgets.json) | frozen | observed | source | verdict |
|---|---|---|---|---|
| maxSwitchPauseMs | 250 ms | 3.009 ms | max engine pause over every measured switch (sweep + workloads + 1000-switch run) (summary.pauseMaxMs) | PASS |
| maxSwitchPauseP99Ms | 100 ms | 0.234 ms | p99 over the 1000-switch run (summary.soakPauseP99Ms) | PASS |
| maxSwitchPreparationMs | 2000 ms | 3.009 ms | total switch duration, an upper bound on the preparation phase (summary.pauseMaxMs) | PASS |
| maxLiveStrategyIndexes | 2  | 2 | max live indexes observed (samples, switch events, baseline) | PASS |
| switchesInSoak | 1000  | 1000 committed / 0 refused | baseline line | PASS |
| maxSteadyP99RegressionPercent | 5 % | +3.87 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxUsefulThroughputRegressionPercent | 5 % | +0.00 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxCpuPerCompletionRegressionPercent | 10 % | +5.00 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| maxPostGcHeapRegressionPercent | 10 % | +0.00 % | worst switched-vs-no-change delta (see the regression table) | PASS |
| repetitions | 5  | [5] measured samples per (workload, arm) | counted over every workload sample in the artifact | PASS |
| switchBacklogSizes | [0, 100, 1000, 10000]  | [0, 100, 1000, 10000] | sizes swept, each with 0/5 reps, 100/5 reps, 1000/5 reps, 10000/5 reps | PASS |

Client-side corroboration, never substituted for the engine's figure: worst client elapsed 3.030 ms (budgets give no client figure).

## Pause sweep (budgets.json switchBacklogSizes)

| requested backlog | switches | pending at switch | pause p50 ms | pause max ms | client max ms | live indexes max | outcome |
|---|---|---|---|---|---|---|---|
| 0 | 5 | 0-0 | 0.116 | 0.119 | 1.708 | 1 | COMMITTED |
| 100 | 5 | 99-100 | 0.223 | 0.262 | 0.281 | 1 | COMMITTED |
| 1000 | 5 | 999-1000 | 0.451 | 0.564 | 0.581 | 1 | COMMITTED |
| 10000 | 5 | 9999-9999 | 2.669 | 3.009 | 3.030 | 1 | COMMITTED |

## Per-workload medians over the 5 repetitions (min-max in brackets)

| workload | arm | reps | useful/s | p99 ms | thread cpu/useful us | alloc/useful B | post-GC heap MB | pending | conserved |
|---|---|---|---|---|---|---|---|---|---|
| low-load | per-function (no change) | 5 | 24.2 (21.7-26.5) | 2.746 | 368.85 | 1153 | 37.16 | 0 | True |
| low-load | per-function -> shared-queue | 5 | 24.2 (21.7-26.5) | 2.644 | 406.70 | 974 | 37.16 | 0 | True |
| low-load | shared-queue (no change) | 5 | 24.2 (21.7-26.5) | 2.636 | 387.33 | 975 | 37.16 | 0 | True |
| low-load | shared-queue -> per-function | 5 | 24.2 (21.7-26.5) | 2.698 | 370.20 | 1158 | 37.16 | 0 | True |

## Return to baseline after 1000 switches, against a no-switch control phase

| figure | switched phase | no-switch control | delta |
|---|---|---|---|
| pending at end | 398 | 398 | +0 (+0.00 %) |
| largest per-function reservation difference | - | - | 0 (+0.00 %) |
| live indexes at end | 1 | 1 | +0 |
| max live indexes during the run | 2 | - | - |
| indexes created / cleared | 1001 / 1000 | - | - |
| admitted | 4273 | 4305 | -0.74 % |
| completed | 3872 | 3905 | -0.85 % |
| useful | 3872 | 3905 | -0.85 % |
| work conserved (admitted == closure) | True | True | - |
| post-GC heap | 37.53 MB | 37.44 MB | +0.23 % |
| switch pause p50 / p99 / max | 0.091 / 0.234 / 0.498 ms | - | - |

### Work conservation across every measured run

- workload runs that conserve work (admitted == completed + expired + removed + rejected + pending + claimed + submitting + in flight): **20 of 20**
- runs with a driver failure: **0**
- runs whose sample cap was reached: **0**
- switch events emitted: **32**
