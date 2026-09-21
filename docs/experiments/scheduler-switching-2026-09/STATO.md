# Scheduler switching campaign — status

## 2026-09-19 — Task 0: baseline frozen; P25 closure recorded as an operator decision

**P25 is not measured closed.** The lifecycle-memory campaign's own status file
(`docs/experiments/lifecycle-memory-2026-09/STATO.md`) ends with an entry dated 2026-09-19
stating the campaign is **incomplete**. The paired requalification that entry names as the
missing prerequisite — a `memory-soak-sync-container.yaml` run at `e35405ee` and again at the
P24 candidate revision, evaluated with the harness fix from that same entry — has **not been
run**. P24's own candidate-only diagnostic (revision
`758d135b8c1b2e7483ae07c96173c51db2510a74`) measured `p24_qualified: false`: two JVM
rss-return criteria fail (control-plane 224.5 MB → 496.4 MB drain maximum; word-stats-java
249.3 MB → 251.9 MB), against the frozen zero-tolerance policy. The JavaScript defect P24 set
out to find is confirmed fixed by measurement.

**Closure, as it actually happened:** the project operator declared P25 closed on **2026-09-19**,
so that this plan (manual scheduler switching, issue #208) may proceed. This is a decision made
by the operator, not a measurement performed by this task, by P24, or by any other entry in the
lifecycle-memory `STATO.md`. Nothing in this repository shows the paired soak having been run,
and nothing here should be read as claiming it was. **The soak was not executed. The campaign
was not measured closed. P24/P25 did not qualify by the frozen policy's own criteria.**

**Attribution limit this leaves for the scheduler-switching work.** Because the paired
requalification never ran, there is no clean pre-scheduler-work memory/latency baseline for the
candidate revision this plan builds on. If a memory or latency regression is observed later in
this campaign, it cannot be cleanly attributed between the scheduler-switching changes and the
still-unqualified candidate revision inherited from lifecycle-memory — the two are confounded
until the missing paired soak is run. Later tasks in this plan should treat any such regression
as ambiguous in origin, not as evidence against the scheduler work specifically, unless the
paired soak is run first to separate the two.

**Task 0 is the only task in this plan authorised to proceed under this declaration**, and it
changed no production code (`src/main` untouched). It froze:

- the baseline (`BASELINE.md`): revision `05f49dcb`, host, three test profiles, GitNexus symbol
  analysis with callers/risk/epistemic per symbol, all UNKNOWN/lower-bound results resolved by
  text search;
- the performance budgets (`budgets.json`), copied value-for-value from the task brief;
- the architectural contract (`docs/architecture/0002-manual-scheduler-switching.md`), which
  records the lock order and the concurrency hazard that Task 4 is scheduled to close.

## What this file does not cover

It is not a re-run or re-evaluation of the P24 measurement, and it performs no new memory-soak
measurement. It records the state Task 0 inherited and the decision that let it proceed.

## 2026-09-21 — Task 12c: the benchmark harness, the campaign, and the frozen-budget comparison

**State: implemented and verified, with one regression-budget miss reported rather than smoothed.**
The gate figure — the pause a manual switch costs — passes by roughly sixty times over.

**What exists now.** `SchedulerSwitchBenchmark.java` (standalone, not a test, no benchmark
library: the JDK is enough) drives the real `SchedulerEngine` with both real preserved strategies
against a real `FunctionCapacityRegistry` and `PendingWorkStore`. `run.sh` resolves
`:execution-runtime`'s test classpath through the new `printTestClasspath` Gradle task — the same
shape as the three sibling modules already have — compiles the harness, stops the Gradle daemon,
and runs it on a fixed pre-touched 1 GiB heap. `summarize.py` turns the JSONL into the tables, and
`RESULTS.md` records them with the command that produced each. `budgets.json` is read by the harness
at run time and echoed into every artifact's header line, so a result cannot be compared against
thresholds other than the frozen ones.

**SHA / provenance.** Campaign artifacts carry `nanofaas.sha = a7e7c47c`
(`Add the scheduler switch benchmark harness and its classpath task`) and
`harnessSha256 = f1941ab47c513241c200fbd420e48c66da1a978f4b2a225cac6f14273e758f63`, because the
harness changed after that commit and the repository SHA alone would not say so.

**Commands (each one is the provenance of a table in RESULTS.md).**

```bash
./run.sh --label=full                                  # the campaign: 260 samples, 164 switch events,
                                                       # 1000-switch phase, one artifact
python3 summarize.py raw/full.jsonl                    # every table in RESULTS.md
./run.sh --label=smoke --parts=backlog --backlogs=100 --repetitions=1
./run.sh --label=diagcc4000 --parts=profiles --profiles=capacity-change --repetitions=3 --window-ms=4000
./run.sh --label=diagcc10000 --parts=profiles --profiles=capacity-change --repetitions=3 --window-ms=10000
./run.sh --label=win10000 --parts=profiles --profiles=head-of-line-blocking --repetitions=3 --window-ms=10000
```

Host quiet throughout: load average 0.12–0.85 on 20 CPUs, no Gradle build in flight (the daemon is
stopped before the measured JVM starts), no other sampling run.

**Result against the frozen budgets.**

| budget | frozen | observed | verdict |
|---|---|---|---|
| `maxSwitchPauseMs` | 250 ms | 3.984 ms (max over 1 140 measured switches) | PASS |
| `maxSwitchPauseP99Ms` | 100 ms | 0.266 ms (p99 over the 1 000-switch phase) | PASS |
| `maxSwitchPreparationMs` | 2 000 ms | 3.984 ms (total switch duration, an upper bound) | PASS |
| `maxLiveStrategyIndexes` | 2 | 2 | PASS |
| `switchesInSoak` | 1 000 | 1 000 committed, 0 refused | PASS |
| `maxUsefulThroughputRegressionPercent` | 5 % | +4.11 % | PASS |
| `maxPostGcHeapRegressionPercent` | 10 % | +0.01 % | PASS |
| `maxSteadyP99RegressionPercent` | 5 % | +76.13 %, 1 of 24 comparisons distinguishable | **MISS** |
| `maxCpuPerCompletionRegressionPercent` | 10 % | +23.83 %, 8 of 24 over budget, 0 distinguishable | **MISS** |

The one distinguishable miss is `capacity-change` switching `shared-queue → per-function`
(p99 48.704 ms → 85.784 ms, disjoint ranges) and it is a **settling transient, not the switch
mechanism**: that arm enters the window carrying the shared-queue phase's deeper standing queue
(`maxBacklog` 82–99 against 26–33), the whole distribution is shifted rather than only the tail, and
the difference decays to +0.6 % with overlapping ranges by a 10 s window. The CPU misses all sit
inside their arms' own dispersion. Work conservation is exact in 240 of 240 runs, and the 1 000
switches return the engine to the no-switch control's state (pending 399 vs 400, live indexes 1,
heap +0.31 %). **No threshold was changed and nothing was adjusted after the fact.** The residual
uncertainty — the same-protocol measurement that would separate "settling" from "regression" needs
a drain between the switch and the window, and is not run here — is stated in `RESULTS.md` rather
than argued away.

**Task 13 is untouched.** The ≥60-minute soak and the HTTP/NanoLab layers belong to Task 13 and
were not run; `switchesInSoak: 1000` is used here only for the switch count and the
return-to-baseline check this task's brief asks for.

**GitNexus (refreshed, then run).** The index was stale (`9ff3f72`); `analyze --index-only` was
re-run and the index is now at `a7e7c47` (`status`: up-to-date, 26 337 nodes, 184 368 edges, 804
flows, 22.0 s). `detect-changes --scope all --repo .` then reported **4 files, 5 symbols, risk level
critical, 737 affected processes** — and that headline is misleading: every changed symbol is in
`docs/experiments/scheduler-switching-2026-09/SchedulerSwitchBenchmark.java`, **no production symbol
appears anywhere in the output** (`grep -E "platform/|src/main"` over it returns nothing), and the
affected-flow list is made of name collisions on the harness's own `Run`/`run`/`main`/`execute`
against unrelated files. The run again degraded its own FTS index (keyword search unavailable) and
again truncated process discovery — 1 931 of 2 131 candidate entry points were never ranked in and
2 445 callees were dropped at the branching cap — so the flow list is a lower bound and an absent
flow means nothing. `git diff --check` is clean. This task changed no production code: the only
non-documentation change in it is the `printTestClasspath` task in
`platform/execution-runtime/build.gradle`.
