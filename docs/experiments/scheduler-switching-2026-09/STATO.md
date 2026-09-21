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

## 2026-09-21 — Task 12c, round 3: the protocol corrected, the CPU budget resolved

**State: the gate question is answered on a protocol that measures what the budgets name.** Two
round-1 comparisons were over budget on a window that included the arm's settling and on a clock
that could not resolve the budget it was checking. Both defects are corrected, both corrections were
specified before the campaign ran, and neither moves a threshold.

**What round 1 got wrong, in its own words.** Round 1 measured the switch mid-window and compared
each switched arm against the strategy it *started* on, so an arm that ran per-function for half its
window and shared-queue for the other half was compared as a mixture against a never-switched arm.
That produced a **+3851 % p99 "regression"** that did not exist: the pooled p99 of every switched arm
sat at the midpoint of the two strategies' own no-change arms. Round 2 moved the switch to the start
of the window. Round 1's regression table is kept in `RESULTS.md` as the artifact the defect is
visible in. **The same harness that produced a false catastrophic miss could equally have produced a
false pass**, which is why the settling rule below is written down rather than chosen.

**The settling rule (fixed before the campaign).** An arm's window contains the queue the previous
strategy left behind, so it is segmented by a rule that reads only the queue depth — never the p99
it segments: `settledDepth` is the median of the depth trajectory (sampled every 50 ms) over the
final quarter of the span; `settleMillis` is the last point whose 500 ms moving average was an
outlier of that arm's own settled distribution (Tukey's fence, 1.5 IQR, floored at one ticket
because a queue depth is a count); the steady figure is the trailing 2 000 ms, the same length round
1 measured; a workload's steady comparison is read only where every arm settled before that window
opens, and reported as not measurable where it did not. Both figures — transient-inclusive and
steady — appear side by side for every arm, and the whole trailing-window grid is in the artifact.

**Answer to the comparability question: the queue converges.** All **24 of 24** switched-vs-control
pairs have a settled level that meets the target's own settled range (`capacity-change` +2.2 % and
+15.8 %, `saturated` +0.0 % and +1.5 %, `switch-under-load` −1.4 % and −4.3 %, `head-of-line-blocking`
+0.8 % and −1.7 %, the rest within five tickets). The arms are comparable at steady state, so the
steady p99 comparison is meaningful. The other outcome the ruling named — a manually switched arm
carrying a persistently different queue — **is not what the data shows**.

**A second defect the investigation exposed, in this harness.** `capacity-change` asked for an
effective concurrency of **8** against a configured **2**; `FunctionCapacityState.setEffectiveConcurrency`
clamps to `[1, configured]`, so the request was silently refused and the workload never changed
capacity in rounds 1 or 2 — **including the run that carried round 1's only distinguishable p99
miss**. It is now a real change (2 → 1, restore at 60 % of the span) and the harness reads the
effective value back into the artifact, so it cannot recur unnoticed. Under the corrected workload
and protocol that workload's steady p99 is **+0.10 % and −5.82 %**: it passes.

**The CPU budget is resolvable, and it is now resolved.** `getProcessCpuTime` is quantised to 10 ms
on this host (measured: a 10 ms busy loop reads exactly 10 000 000 ns every time), which cannot
resolve 10 % on a workload doing tens of completions per window. `getThreadCpuTime` resolves to
microseconds (10 005 232 ns for the same loop). Round 3 reports CPU per useful completion from the
sum of every live thread's CPU time and emits the process figure beside it. The resolution claim is
a measurement, in `raw/clock-resolution.txt` with its source `ClockTest.java`.

**Result on the corrected protocol** (`raw/steady.jsonl`, 240 samples, 120 switches, all arms
settled, work conserved in 240 of 240):

| budget | frozen | observed | verdict |
|---|---|---|---|
| `maxSteadyP99RegressionPercent` | 5 % | worst +9.51 %; 4 of 22 measurable comparisons over budget, **0 distinguishable** | over budget, **not distinguishable** |
| `maxCpuPerCompletionRegressionPercent` | 10 % | worst +22.06 %; 3 of 22 over budget, **0 distinguishable** | over budget, **not distinguishable** |
| `maxUsefulThroughputRegressionPercent` | 5 % | worst +2.09 % | PASS |
| `maxPostGcHeapRegressionPercent` | 10 % | worst +0.01 % | PASS |
| `churn-drain` (2 comparisons) | — | stops its traffic by design; no arrivals in the steady window | **not measurable** |

Seven comparisons exceed their threshold and **every one sits inside its arms' own five-repetition
ranges**; the brief's disposition for that case is to declare the result **not distinguishable** and
keep both schedulers. The mechanism budgets are rounds 1–2's, recorded as passing and not re-run;
the settlement campaign corroborates the pause figure independently at 0.278 ms.

**GitNexus.** The index was refreshed (26 337 nodes, 184 368 edges, 804 flows, up-to-date at
`a7e7c47c`). `detect-changes --scope all` over the working tree before this commit: 4 files, 5
symbols, all of them in `docs/experiments/scheduler-switching-2026-09/SchedulerSwitchBenchmark.java`
— **no production symbol appears anywhere in the output** — with a `critical` headline driven by name
collisions on the harness's own `Run`/`run`/`main`/`execute` and 730-odd unrelated flows. The run
again degraded its own FTS index and truncated process discovery (1 931 of 2 131 entry points never
ranked in), so the flow list is a lower bound.
