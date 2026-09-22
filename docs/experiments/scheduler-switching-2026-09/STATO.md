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

**How the rounds are numbered — canonical, and the numbering every document in this task uses.**
Eight rounds ran: **round 1** the campaign; **round 2** the protocol correction (the switch moved to
the start of the measured window); **round 3** the settling correction and the CPU budget's
resolution (`5ecd6def`); **round 4** the claim and provenance corrections (`90e0e4ac`, `7f2c44c0`);
**round 5** the fix wave's own claims (`b766cc51`); **round 6** the retracted-claims sweep
(`7e70f9c4`); **round 7** the table count, the CPU clock moved to the tool, and the provenance
citations (`174a1a03`); **round 8** the numbering itself and the repair of the provenance citations
(the commit carrying this paragraph). The campaign report (`task-12c-report.md`, in the plan's
gitignored ledger) uses these numbers for rounds 1–3 and labels the later ones "Fix round 1/5" …
"Fix round 5/5": fix wave *k* there is **round *k + 3*** here. A round reference anywhere in this
file, in `RESULTS.md` or in that report means the numbering on this page.

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
python3 summarize.py raw/steady.jsonl                    # the corrected-protocol tables, the resolving power and the pairing table
python3 summarize.py raw/full.jsonl                    # the round-1 tables, and the mechanism rows
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
| `maxUsefulThroughputRegressionPercent` | 5 % | +2.09 % (worst of the 22 measurable comparisons, `raw/steady.jsonl`) | PASS |
| `maxPostGcHeapRegressionPercent` | 10 % | +0.01 % | PASS |
| `maxSteadyP99RegressionPercent` | 5 % | +76.13 %, 1 of 24 comparisons separable *(round 1's reading, on a protocol later found to pool two strategies)* | **MISS** |
| `maxCpuPerCompletionRegressionPercent` | 10 % | +23.83 %, 8 of 24 over budget, 0 separable *(round 1's reading, through the 10 ms process clock)* | **MISS** |

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
on this host (measured: a 10 ms busy loop reads 10 000 000 ns in six of eight trials and
20 000 000 ns in the other two — a 10 ms step that does not report the interval faithfully inside
it), which cannot
resolve 10 % on a workload doing tens of completions per window. `getThreadCpuTime` resolves to
microseconds (10 000 640-10 007 536 ns for the same loop). Round 3 reports CPU per useful
completion from the
sum of every live thread's CPU time and emits the process figure beside it. The resolution claim is
a measurement, in `raw/clock-resolution.txt` with its source `ClockTest.java`.

**Result on the corrected protocol** (`raw/steady.jsonl`: 240 samples, 144 `"kind":"switch"` events
of which **120 are counted as measured pauses** in `summary.switchPausesRecorded` — the other 24 are
warm-up switches — all arms settled, work conserved in 240 of 240):

| budget | frozen | observed | verdict |
|---|---|---|---|
| `maxSteadyP99RegressionPercent` | 5 % | worst +9.51 %; 4 of 22 measurable comparisons over budget, 0 separable | over budget; **no regression established — the test cannot resolve 5 %** |
| `maxCpuPerCompletionRegressionPercent` | 10 % | worst +22.06 %; 3 of 24 over budget, 0 separable | over budget; **no regression established — the test cannot resolve 10 %** |
| `maxUsefulThroughputRegressionPercent` | 5 % | worst +2.09 % | PASS |
| `maxPostGcHeapRegressionPercent` | 10 % | worst +0.01 % | PASS |
| `churn-drain`'s 4 window rows | — | stops its traffic by design, so its steady window holds no arrivals. Its 4 whole-run rows (CPU, heap) are measured and all pass | **4 not measurable, 4 pass** |

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

## 2026-09-21 — Task 12c, round 4: the claim corrected, provenance made traceable

**State: the deliverables now say what the measurement supports.** The review's central finding was
that my "0 distinguishable" was a property of the test, not of the effect, and it was right. Nothing
about the settlement protocol changed; what changed is what the null is claimed to mean.

**The claim, corrected.** The arms' own median spread across five repetitions is **16.1 % for p99**
and **26.9 % for CPU per useful completion**, against budgets of 5 % and 10 %. Shifting each control
arm by a *uniform multiplicative* factor — the most favourable possible case for a range-overlap
test — shows the campaign could not have separated a real 5 % p99 effect (6 of 22 comparisons would
read it) or a 10 % CPU effect (**0 of 24** at 10 %, 11 of 24 only by 30 %). So the deliverable now
says **"no regression established at this campaign's resolution, which is coarser than the budgets"**
wherever it said "not distinguishable", and carries that resolving-power table on the artifact
itself (computed by `summarize.py`, not quoted). Post-GC heap is the one metric with teeth — 24 of 24
at +5 %, arm spread 0.0 % — and it passes. The disposition is unchanged and is the brief's own:
«dichiarare risultato non distinguibile e conservare entrambi gli scheduler».

**The mechanism verdicts are untouched**, and were re-derived line by line from `raw/full.jsonl`:
pause max 3.984176 ms against 250 (60×) — the first switch of the process, on an empty engine and a
cold JIT, the harshest available reading; soak p99 0.266335 against 100; 1 140 pauses; 1 000
committed / 0 refused; 2 live indexes; return-to-baseline pending 399 vs 400; heap +0.31 %.

**What this round fixed.**

- **The settling rule's validity check was documented but not wired.** `steady_regression` never
  consulted `settleMillis`; its only gate was an arrivals floor. It is now gated per metric — the
  two window-derived metrics on the settling rule *and* arrivals, the two whole-run metrics on
  neither, because they have no steady form. Verified by forcing a workload's arms unsettled: its 4
  window rows read NOT MEASURABLE while its 4 whole-run rows stay measured.
- **The `churn-drain` exemption was over-broad.** Calling CPU-per-completion and post-GC heap
  "steady" dropped two plan-mandated comparisons for a workload that stops its traffic. Both are
  whole-run quantities and both are answerable: **+7.26 % / +7.67 % CPU, +0.00 % / +0.00 % heap, all
  inside budget.** Only its four window rows (steady p99 and steady useful throughput, one per
  direction) are genuinely unmeasurable; its four whole-run rows pass, and the table says so.
- **The coverage table contradicted the harness** on four counts (`capacity-change` still described
  as raising 2 → 8, which is the defect round 3 removed; a stop offset of 1.8 s against a 4.8 s
  reality; event offsets described as absolute milliseconds when they are percent-of-span; and a
  `cpu/useful` column labelled process CPU — see the correction below). All four fixed, though the
  fourth fix was itself in the wrong direction: `raw/full.jsonl` carries no thread CPU field, so
  that table's process label was right and round 4 relabelled it thread. **Round 7** is where that is
  put right — see *How the rounds are numbered* at the head of this Task 12c record.
- **The pairing claim was false and one piece of evidence leaned on it.** The four arms do *not* see
  the same arrivals: one `Random` drives three draws per arrival, so 54 of 60 (workload, repetition)
  groups differ in `offered`, by up to 301 tickets. Six groups agree, five of them `low-load`'s and
  the sixth `unqueued` repetition 4. **The spread is not a function of the offered rate** — the
  widest is at 1 148/s measured and a higher-rate workload spreads less (`hot-plus-500-sporadic`,
  2 219/s, 4–81 against `switch-under-load`'s 87–301) — so no rate-scaling story is told; two
  mechanisms are present and this campaign does not measure them apart, and `raw/host-quietness.txt`
  carries the per-workload table. Corrected in `RESULTS.md`, in the harness javadoc and in
  `raw/host-quietness.txt`. The pairing fix itself is deliberately **not** half-applied here.
- **Two figures could not be traced to an artifact.** The quoted clock values were from an earlier
  run than the committed evidence; they are now the artifact's own numbers, re-measured, and
  `ClockTest.java` carries a package declaration. Round 1's +3851 % was quoted with no artifact
  (its run was overwritten); it is replaced by a **recomputed +3805.3 %** that `summarize.py`
  derives from `raw/steady.jsonl` under a stated command, with the matched pairings beside it at
  −0.5 % and −4.4 %.
- **`run.sh` no longer trusts a committed classpath cache.** It validates that the module's compiled
  output is named and that every jar exists, and re-resolves loudly otherwise — the committed cache
  holds the implementer host's absolute paths.
- **`git diff --check` is no longer misreported.** It reports 96 lines of output (48 flagged lines,
  two capacity read-back messages once per `capacity-change` run) in `raw/steady.err`. They stay as
  captured: the artifact must keep matching the `harnessSha256` in its own header, and captured
  evidence is not edited to satisfy a linter. The one line it flagged in `RESULTS.md` — a blank line
  at EOF — is fixed, because a document is not captured evidence.

**Two limits added to the limitations section**, neither requiring a measurement. The **p99 figures
are censored** by the contract deadline: under load, queueing becomes expiry rather than latency, so
the 4 loaded p99 rows sit at their cap and are structurally insensitive (useful throughput is what
carries those workloads — the spec's own point). And **the async deadline this harness applies is
stricter than the one that ships**: production passes `queueDeadline = null` for the async front
(`EngineInvocationEnqueuer.java:137`) and sets a deadline only for sync
(`EngineSyncQueueGateway.java:206`), while the harness stamps the profile contract on every ticket
including ASYNC — so its async expiry and dependent p99 figures bound a stricter policy than
production, not a prediction of it.

**Return-to-baseline re-run against the harness as committed at `90e0e4ac`** (controller
instruction; **not** the harness the repository holds today, whose digest is `353ee278`):
artifact `raw/baseline.jsonl`, harness digest `d42cd7f7…`, 1 000 switches committed and 0 refused, pending
398 against the control's 398 (**delta 0**), largest per-function reservation difference **0
tickets**, one live index in both phases, work conserved in both. The mechanism rows still come from
`raw/full.jsonl`, whose header names the earlier harness digest `f1941ab4…`; that provenance is now
stated in `RESULTS.md` rather than left implicit.

**On the "not re-run, on instruction" claim.** The instruction is the controller's, given during
this task's fix rounds: *keep the mechanism verdicts, do not re-run the campaign to look for a
different answer.* It is recorded here because until now it lived only in the campaign ledger
(gitignored) and was therefore unverifiable from inside the repository.

## 2026-09-21 — Task 12c, round 5: the fix wave's own claims corrected

**State: text and tool prose only. No measurement re-run, no threshold moved.** The scoped
re-review confirmed the framing correction came back honest — the headline table still carries
"over budget" with +9.51 % and +22.06 % spelled out, the regression table still prints MISS on all
seven comparisons, the limitation is stated as one of the instrument, the metric with teeth (heap,
spread 0.0 %, 24 of 24) is named as passing, and the resolving-power table is computed by
`summarize.py` rather than quoted. What this wave fixes is defects the wave itself introduced.

- **The transient-inclusive column had become a duplicate of the delta column.** `steady_regression`
  was handed the same extractor for both, so every window row printed its delta twice and the
  whole-span figures — including the informative ones (`queued` +9.36 % steady against **+22.68 %**
  whole-span) — were gone while the label promising them stayed. Restored: the transient counterpart
  of a window metric is now the whole-span figure, and whole-run rows say `— (no steady form)`.
- **The clock evidence contradicted its own data.** `raw/clock-resolution.txt` said the 10 ms loop
  read 10 000 000 ns "five times and 20 000 000 ns three times"; its table says six and two. Fixed to
  the data, in the file this task cites as the evidence for the CPU budget's adjudication.
- **"Only `low-load` stays in lockstep" was one group too strong.** Six of sixty groups agree:
  `low-load`'s five, and `unqueued` repetition 4 — which the stated mechanism ("at 20/s no extra path
  fires") cannot explain. The count is corrected. The sentence written to replace it then claimed a
  relation to the offered rate that the same data refutes (the widest spread is at 1 148/s measured,
  and a higher-rate workload spreads less), so it was replaced again with what the artifact supports:
  the spread varies by workload, it is not monotone in the rate, and the causes are not separated.
- **`churn-drain`'s row count was wrong** — the table has 8 rows, 4 not measurable and 4 passing, not
  2 and 2. Corrected.
- **The tool was not brought to the corrected wording**: it printed "0 of those distinguishable"
  where the document says "separable". Aligned.
- **`legacy_regression`'s docstring described the wrong pairing**, contradicting both the code and
  its own inline comment; it now describes what the function does.
- **The reproduction block and the "not retyped" claim were both wrong.** The block named
  `raw/full.jsonl` for tables that come from `raw/steady.jsonl`; the claim said every table is the
  script's output when the headline and return-to-baseline tables are hand-written transcriptions.
  Both corrected, and the two commands that do produce the generated tables are now given.
- **The provenance table cited a revision that does not exist**: `0062ab2a` is a pre-amend object,
  unreachable from `HEAD`; the harness was committed at `90e0e4ac`, and the diff it describes —
  `git diff 5ecd6def 90e0e4ac -- …/SchedulerSwitchBenchmark.java`; both revisions, because a bare
  `5ecd6def` is compared against the working tree and returns a different count — is 2 lines removed
  and 11 added, not four and nine, checked with `git diff` rather than from memory.
- **`raw/baseline.jsonl`'s header pair does not exist either** — it records revision `3f352ee4` with
  harness `d42cd7f7`, because the run used the **uncommitted working tree**. The numbers are
  unaffected, and the digest is that of the file *as committed at `90e0e4ac`* — not of the committed
  file today, which round 6's javadoc edit moved to `353ee278`. The text now says which is which.
- **The resolving-power caption overclaimed its own method**: the code shifts the *control* arm's own
  five values and tests separation from itself, so the table is a deterministic restatement of the
  arms' spread and not an independent power calculation. Reworded to what the code does. It is the
  more conservative reading either way, and the verdicts do not move.
- The duplicated pairing table — hand-written in the narrative and generated below it, with different
  column labels for identical numbers — is now generated in one place, with the narrative pointing
  at it.

## 2026-09-21 — Task 12c, round 6: the retracted claims swept out

**State: text and one javadoc. No measurement re-run, no threshold moved.** Rounds 4 and 5 each
corrected a retracted figure where it was cited and left copies standing elsewhere, so this round
grep'd the whole directory for every retracted item rather than re-reading the places that were
named. The greps and their results are in the report.

**The harness javadoc was edited** (controller decision), which moved its digest from `d42cd7f7` to
`353ee278`. It still carried the retracted pairing claim and the retracted rate-scaling story
verbatim, and a retracted technical claim standing in source is believed by whoever reads the file
rather than the report — this campaign's whole lesson. The digest moving is the discipline working:
the mechanism exists so a changed harness is visible, and the provenance text now says which build
produced which artifact rather than implying the artifact names its own revision.

**Every artifact in this campaign was produced from an uncommitted working tree**, which is why
three of them record a revision-and-digest pair that does not exist in history: `raw/full.jsonl`
records `a7e7c47c` with `f1941ab4` (committed at `83522ee8`), `raw/steady.jsonl` records `83522ee8`
with `831828d6` (committed at `5ecd6def`), and `raw/baseline.jsonl` records `3f352ee4` with
`d42cd7f7` (committed at `90e0e4ac`). The digest identifies the build; the revision records where
the tree was. `RESULTS.md` carries the table.

**The spread of the four arms' `offered` counts is not a function of the offered rate**, and the
sentence that said it was — written in round 5 to replace the one it replaced — was refuted by its
own list. The widest spread is `switch-under-load`'s 301 tickets at 1 148/s measured, while
`hot-plus-500-sporadic` at 2 219/s spreads 4–81. The per-workload table is now in
`raw/host-quietness.txt`, the claim is gone from all three documents, and what is stated is that two
mechanisms are present and this campaign does not measure them apart.

**The generation count is eight of fifteen table blocks**, classified by exact string match against
the tool's output rather than by inspection. *(Round 6 stated this as "seven of fourteen". Both
numbers were wrong: the file holds fifteen blocks and eight of them match the tool, and "fourteen" is
reachable only by counting two of the three provenance tables as one, which the text did not say.
Round 7 counted it and corrected the enumeration and the counting rule in `RESULTS.md`.)* Doing it by
string match caught a stale table: the round-1 per-workload medians had been computed by an older
`summarize.py` under a header the tool no longer prints, so the table could not be regenerated from
the committed tool. It is re-spliced from the current tool. *(Round 6 also claimed the re-splice
"brings its CPU column onto the **thread** figure"; that is backwards — `raw/full.jsonl` carries no
thread CPU field, so that column is the **process** clock, and relabelling it "thread" put a false
clock in front of a reader. Round 7 restored the process label and made `summarize.py` read the label
off the field it actually read, so it cannot drift again.)*

## 2026-09-21 — Task 12e: the refactor's own cost, old loop against new engine

**State: implemented, measured, verified — and the comparison the spec asked for now exists.** Task 0
froze thresholds and a revision but no performance figures (`BASELINE.md:155`), so §11's obligation —
*«verificano regressioni rispetto alle implementazioni precedenti»* — was unmet: Task 12c's harness
can only exchange strategies **inside** one engine. This step produces the missing number.

**What exists now.** `OldLoopComparison.java` drives the old async loop (`Scheduler` over
`QueueManager`/`FunctionQueueState`, dispatching into `InvocationDispatch`) as the subject of its own
arm, and the new engine with `PerFunctionSchedulingStrategy` — the port of that same loop — as the
other, **in one JVM**, with one shared driver, alternating repetition by repetition. `run-old.sh`
builds it and runs it; `old-vs-new.py` turns its JSONL into the tables, importing `summarize.py`
rather than restating its settlement rule; `raw/old-vs-new-analysis.txt` is that tool's output
verbatim and `OLD-VS-NEW.md` is the deliverable. `budgets.json`, `SchedulerSwitchBenchmark.java` and
`summarize.py` are **unchanged**.

**It is a profile comparison — level 2 of the spec's taxonomy, not level 1.** The ruling recorded
earlier in this ledger that cited level 1 for this comparison was wrong and stays corrected;
`OLD-VS-NEW.md` says so in its first paragraph. Level 1 already exists in Task 12c's harness.

**The design, in one line each.** One arrival script per (workload, repetition), materialised before
either arm runs and replayed to both, so the arms are offered the *identical sequence* and a
per-repetition difference is paired — which the committed harness could not do and says so in its own
comment. Corpus, span, warm-up, depth cadence, trailing-window grid and JSONL schema come from the
committed harness, which this file is compiled together with, so none of them can drift. One
reflected constructor (`QueueManager`'s, package-private) is the entire seam; nothing in the engine's
new API is reached. The service model publishes a due time and returns, holding the lease — the
`T2BatchBench` busy-wait precedent is not copied.

**Result — the nulls, with their resolution.** Stated beside every figure because a null without one
is an assertion about the instrument. The headline resolutions are **medians across workloads** —
p99 (steady 3.5 %, whole-span 5.4 %), useful throughput (0.01–0.03 %), post-GC heap (0.01 %) — and
the median is pulled down by profiles whose paired differences already agree in sign and therefore
contribute 0 by construction, so it is never any one workload's figure. The artifact carries the
per-workload table, and the numbers that matter are the workloads': `low-load`'s steady p99 resolves
to **9.36 %** and `unqueued`'s whole-span p99 to **11.41 %**, against `queued`'s 33.49 % and 43.48 %.
No regression is established for those metrics on any expiry-clean profile. One budget **MISS**:
`low-load`'s steady p99, **+5.40 %** — reported as a MISS, not excused; in absolute terms 2.570 ms →
2.793 ms against a 100 ms contract, with the five paired differences running −19.52 % to +9.36 %,
i.e. straddling zero, and 5.40 % below that workload's own 9.36 % resolution. The unpaired design
Tasks 12c had could not have adjudicated the 5 % p99 budget: its arms' own medians spread 17.5–22.1 %
between repetitions, and a perfectly uniform 5 % shift of the control arm separates the arms in
**1 workload of 6** (whole-span p99) and **1 of 5** (steady); CPU per useful completion is the same,
1 of 6. Only post-GC heap was adjudicable unpaired, and only because its arms agree to 0.01 %.

**Result — the one consistent direction.** The new engine allocates **more per useful completion** in
every repetition of every expiration-clean profile and of `queued` too: +5.5 % (`churn-drain`),
+14.7 % (`mixed-kind-retry`), +18.5 % (`queued`, flagged `*`, whose confound is an expiry share of
1.0–2.5 % of its offers), +27.5 % (`unqueued`), +27.8 % (`low-load`), 5-of-5 same-signed each; the
direction holds on the four genuinely clean profiles without `queued`. Those are the per-profile
**medians**, spanning +5.5 % to +27.8 %; the repetition-level deltas span **+1.59 % to +45.92 %** and
the window-total figures **+1.58 % to +45.84 %**, both same-signed — the finding is not rounded down
to the medians. No frozen budget covers allocation, so there is no verdict — but the direction is
consistent with the named hypothesis (the old loop amortises one visit over up to two dispatches; the
engine's pass claims one). The mechanism is **not** established by this harness and the document does
not guess at it.

**Result — CPU per useful completion is the metric the pairing did not sharpen.** Its paired spread
(43.15 %) exceeds its unpaired arm spread (33.31 %), so its noise is intra-run JVM activity, not host
drift, and the 10 % budget is below this design's resolution on it. Four `MISS` rows result: three
with the five repetitions on both sides of zero (not resolved), and `mixed-kind-retry` at
**+29.02 %** (range +17.96…+47.92, 5-of-5 positive, every repetition above budget), corroborated by the
window-total CPU cross-check at +29.02 %. On that one profile the cost is measured; elsewhere the
direction is suggested and not separated. The completion wiring does charge the two arms' driver
differently (M6) — the old arm does conditional micrometer bookkeeping and a conditional wake, the
new one an unconditional lock-and-notify — but **which costs more was not measured**, and an earlier
revision's claim that it pointed against the old arm has been withdrawn rather than kept as an
argument.

**`saturated` is established, huge, and NOT a loop result.** 77 % of its offers are expired by the
engine and none by the old loop, which has no deadline: the old loop's 512-ticket queue stays
permanently full of work already past contract, refuses 14 149 fresh offers at admission, and yields
34 useful completions against the engine's 3 655. The row is flagged in the artifact and the document
attributes none of it to the claim cadence — it is the **expiry policy** (M3), which this comparison
chose, and declared, not to equalise: a reaper that shadowed the engine's is not expressible through
the old loop's API (`poll`/`pollForDispatch` are head-only; `closeAndDrainQueued` closes the state)
and would have been a new driver policy masquerading as the old loop's behaviour.

**`queued` is the cautionary example.** The single-repetition diagnostic showed the old loop 68.5 %
*worse* on p99; across five repetitions it is 25.5 % worse with one repetition +33 % — the range
crosses zero, so nothing is established. That gap is why there are five repetitions and a paired
design.

**Coverage: 6 profiles × 2 arms × 5 repetitions = 60 runs, 60 of 60 conserving work**, zero driver
failures and zero sample-cap overruns. Covered: `low-load`, `saturated`, `unqueued`, `queued`,
`churn-drain`, `mixed-kind-retry`. Excluded, with the reason in the document: `hot-plus-500-sporadic`
(M2 — no alignment exists), `heterogeneous-burst`, `capacity-change`, `switch-under-load` (driver
features not implemented; the harness **refuses** them loudly rather than running a workload whose
`note` describes something that did not happen), and **the sync arm**, not attempted in this pass —
M8 makes it comparable only when `syncQueueMaxQueueWait == contractMs` per profile, the committed
`SyncQueueService`/`SyncQueueInvocationEnqueuer` wiring exists, and nothing in this step speaks for
the shared-queue side. `churn-drain`'s two window metrics are NOT MEASURABLE because it stops its
traffic at 60 % of the span and its steady window holds no arrivals.

**Corrections to the 12e brief, from the code.** The brief's included set names
`head-of-line-blocking`, which is defined by `notReadyCount = 5`. The gate it exercises is
`EngineReadiness`, and **that does not exist at the pre-refactor revision** — `git grep -l
EngineReadiness -- '*.java'` returns 0 files at `05f49dcb` and 15 at HEAD, and `notReady` returns 0
at OLD. (An earlier revision of this entry said "readiness exists nowhere in the old tree" on the
strength of a `git grep` over `platform/*/src/main`, which matches nothing at all under that tool —
the evidence was void and the sentence false as written; what the old tree has is *deployment*
readiness, `DeploymentReadiness.ensureReady`, a different concept consulted from the execution path
and a wait-for-wake rather than a dispatchability predicate.) Driving the profile on the old arm
would have had 5 of its 6 functions dispatch work the engine refuses — a policy difference, not a
loop one, in the old arm's favour. It is excluded as a miscategorised inclusion, not a skipped
profile; the brief's mismatch list does not mention readiness at all and its numbering skips M4 and
M10. The brief's other figures were checked against the code and hold: the old loop's batch of two
(`Scheduler.java:31`, `:208`), the engine's one claim per pass, the two loop classes' byte-identity
across the refactor, and `FunctionCapacityRegistry`'s unchanged FQN.

**Provenance, and the one artifact whose pair is not in history.** `raw/old-vs-new.jsonl` (the
campaign) records revision `c64da0717f9ec47f60c70108b019d2b91d02c9b1` (`Add the old-loop arm and its
driver`) with harness digest `761908913d93…` and committed-harness digest `353ee27881ef…` — **a pair
that exists in history**, because the corpus the harness is measured on *is* that committed file.
`raw/smoke-old.jsonl` (the smoke run, committed with the harness) records revision
`7beabc332d356de6cbdf290328fafe7a7f39b95f` with the *same* harness digest, because it ran before the
harness was committed: at `7beabc33` there was no `OldLoopComparison.java`. That is Task 12c's own
pattern and its own wording — the digest identifies the build, the revision records where the tree
was — and it is stated rather than left for a reader to discover. Both artifacts' digests match the
files as committed (checked, not asserted).
The host was quiet: 122 load-average samples spanning the campaign's 602 s, 1-minute load average
**min 0.10, median 0.29, max 0.61** on 20 processors (record truncated to the campaign window and
reduced to a strictly increasing series; the truncated lines are post-campaign and one out-of-order
duplicate — no sample was altered). The smoke run committed with the arm is
`raw/smoke-old.jsonl` (both arms: 208 offered, 208 admitted, 208 useful, closure 208, conserved), and
its `offered` figure is checkable independently against the arrival script.

**Revision — fix round 1/5, one class swept rather than four sites.** The review that closed the
initial dispatch named four findings, all of the same shape: **prose outrunning the artifact** — a
cross-workload resolution median quoted as one workload's (`low-load`'s MISS read against 3.5 %
instead of its own 9.36 %, which made the sentence contradict its own conclusion), "every unconfounded
profile" containing the `queued` row the coverage table flags `*`, a readiness grep whose pathspec
(`'platform/*/src/main'`) matches nothing at all so its zero was void, and an M6 direction inference
that was asserted and not measured. All four are fixed in `OLD-VS-NEW.md`, and because Task 12c cost
this campaign five rounds by fixing named sites and leaving the class, the section **§12** records the
five sweeps run for the class afterwards — aggregated figures used as a workload's, scope words
containing a flagged row, numbers whose unit is not the sentence's, claims repeated at two precisions,
and evidence that does not reproduce. The sweeps found four things the fix round had not named:
two more aggregate-scope errors in §9.1, a `queued` throughput direction that had been left implicit,
the fact that only one of the three `offered` differences is the reactive retry draw (the other two
have equal retry counts and are the window-close race), and a JVM CPU share with no artifact behind it
— replaced by the harness's own `threadCpuNanos` (140.6–658.0 ms per 8 000 ms window, median 276.3 ms,
1.8–8.2 % of one core). Two claims were **withdrawn** rather than softened: the M6 direction, and the
arrival script's "226 over its whole horizon", whose method was not committed; the analyzer now prints
the `offered` pair check instead (identical in 27 of 30 pairs, three differing by one). No measurement
was re-run, `budgets.json` was not touched, and the analyzer's regeneration of
`raw/old-vs-new-analysis.txt` was re-verified byte-identical after the tool gained the per-workload
resolution and offered-pair tables.

**Revision — fix round 2/5, the instrument rather than the targets.** Round 1's fixes were verified
at every named site, and the re-reviewer reproduced the tables at 0 mismatches — but it also found
the class again, and in a specific place: **the one table this document had been reconciled
programmatically was the one table nothing was ever found in**, while the hand-read ones kept
yielding defects. So the diagnosis is not that prose drifts; it is that verification was **partial**.
Round 2 therefore replaces thematic sweeping with a committed reconciler, `reconcile-12e.py`, whose
output is `raw/reconciliation-12e.txt`: **797 table cells recomputed cell-by-cell (0 mismatches) and
90 registry entries recomputing 91 numeric prose claims (0 mismatches)**, with 290 non-claim numbers
classified into 15 named citation classes and **20 left over, listed in full** in `OLD-VS-NEW.md` §12
and in the artifact. A table with no checker fails the reconciler's run, and so does a registry entry
whose claim has been reworded away, so the instrument cannot silently stop guarding.

It found in the deliverable three things no sweep had: **§9.4's `completed / useful` cell paired the
single-repetition diagnostic run's `completed` with the campaign's `useful`** (`4181` against the
campaign median 4267 — the cross-artifact error the coordinator named, found mechanically rather
than by eye), a §8 resolving-power cell giving `thread cpu per window` at +50 % as `0` where the
analyzer prints `1`, and §9.5's "the new engine's 50 ms" for `queued`, whose new-arm settled time is
**0 ms** (50 ms is `saturated`'s). All three are fixed. It also caught four bugs in itself first — a
table parser that mistook `|---|` for the end of a table, two unit conversions, a rounding tolerance
too tight for prose that rounds, and claims spanning a line break it could not match.

**One true claim was restored rather than dropped:** round 1 removed the arrival script's horizon
count as unverified, and the reviewer's replay showed it was true and derivable — the script's
horizon is 10 500 ms (`OldLoopComparison.java:773`) and 9 500 ms is the driver's window close, giving
226 and 208 respectively. Both are now in the deliverable with the correct horizons, and the clause
that justified them by the wrong one is corrected.

**Revision — fix round 3/5, the mechanism that let the earlier rounds miss.** Round 2's reconciler was
verified genuine (it reproduced all three defects it claimed to find, from the base commit, plus seven
further perturbations, and the 226/208 horizon counts were re-derived from the committed Java) — but
it had a hole with a name: **it scanned digits, not words.** Numbers spelled out — "two of the six
profiles", "the three that differ" — were invisible to both its registry and its inventory, and every
one of the four falsehoods round 3 had to fix was written in words. The reviewer also demonstrated the
weak class rather than arguing it: swapping `queued`'s throughput median for its CPU median, a value
that genuinely exists in the analyzer's output, still returned PASS.

So round 3 closes the mechanism. A **word pass** now runs over the same prose with its own registry,
inventory and classifier, and a stale word entry fails the run exactly as a digit one does. The weak
class now identifies the **subject its clause names** and requires the value to appear in that
subject's own row, which is what catches the demonstrated swap (it is listed as «names queued / useful
throughput, where this value does not appear»). `git_files()` — written and never called, so that
changing §5.7's grep count from 25 to 99 still passed — is wired and perturbation-tested. §12's
instrument counts are now computed by a second registry that evaluates the whole document including
that section, which is how "11 tables" became 13: the document has thirteen and the reconciler checks
twelve. Of the registry's 140 entries, **2 declare themselves literals** rather than hiding among the
recomputed ones. And the instrument's bite is itself measured: `reconcile-12e.py --perturbations`
applies ten careless edits and requires failure on each — **all 10 caught**, committed as
`raw/reconciliation-perturbations.txt`. Building it found that the MISS's own `+5.40 %` had no registry
entry at all.

**Coverage now: 797 table cells and 146 prose claims recomputed at 0 mismatches, 18 + 9 named classes,
and an unreconciled residue of 0** — down from 20. The four falsehoods round 3 fixed were the
finding-7 replacement under-counting the profiles at `0.00` and omitting the three metrics where the
effect is largest; the same miscount surviving in §9.1 and in the report; a false claim that the §5.7
greps were reconciled when none were; and a table count stale by the two tables the previous round
added. Finding 9 is fixed too: §9.1's whole-span p99 range had excluded the profile that supplies its
own `0.00` endpoint. §12's exemption is now stated where a reader looks rather than only in the
script.

## 2026-09-21 — Tasks 0-12 consolidated: the git-tracked record, written at Task 12d

**Why one entry, and why now.** Until Task 12c this file had been touched by exactly one commit
(`d8a30879`, Task 0). Tasks 1 through 12ab completed — every one with SHAs and a review verdict — and
none of them updated it. What recorded them is `.superpowers/sdd/2026-09-16-manual-scheduler-switching/progress.md`,
which is gitignored (`.gitignore:35` = `.superpowers/`) and is scheduled for deletion when the
workspace is torn down. A prior ruling (recorded in that ledger) decided against retro-fitting eleven
separate entries, because they would be reconstructions rather than records. This section is that
ruling's remedy: **one consolidated entry covering tasks 0-12, per task: SHA, what was verified, the
command, the evidence, the limits.** It is written at Task 12d from the ledger and every SHA is
confirmed against `git log`. The Task 12c and Task 12e sections already above carry their own detail
and are not repeated here; this entry states where the record for each lives.

**On commands.** Where the ledger preserves the literal command line it is quoted verbatim. Where it
records only the outcome and the counts, this entry names the Gradle task that produced them rather
than inventing a command string that was never written down.

**On the two questions Task 12 asks.** Task 12c measures what a *manual switch* costs. Task 12e
measures what the *refactor itself* costs. They are distinct questions with distinct resolving power
and they are kept apart here and in `TASK12.md`. `budgets.json` has not been modified by any task
below.

### Task 0 — baseline frozen; P25 closure recorded as an operator decision
- **SHA** `d8a30879` (`05f49dcb..d8a30879`). Docs-only; verified no `src/main` file in the diff.
- **Verified** the baseline `BASELINE.md` at revision `05f49dcb` (host, three test profiles, GitNexus
  symbol analysis with callers/risk/epistemic per symbol, every UNKNOWN/lower-bound resolved by text
  search); `budgets.json` copied value-for-value from the task brief (11 keys, order and values
  independently confirmed by the reviewer); the architectural contract
  `docs/architecture/0002-manual-scheduler-switching.md` and its lock order.
- **Command** the three baseline suite runs, with `--rerun-tasks` (not in the plan's literal command
  text; the reviewer did not flag it).
- **Evidence** async-queue+runtime-config **901 tests / 0 fail / 5 skip**; sync-queue+runtime-config
  **954 / 0 / 5**; runtime-config only **835 / 0 / 6**. The skips are module-gated JUnit assumption
  aborts. `BASELINE.md`, `budgets.json`, ADR 0002.
- **Limits** (i) GitNexus FTS was degraded and process-flow discovery truncated on this run, so no
  later task may lean on Task 0's graph pass — each re-runs its own. (ii) The P25 paired
  requalification soak was never run, so a memory or latency regression observed later cannot be
  attributed cleanly between the scheduler work and the still-unqualified candidate revision
  inherited from lifecycle-memory. (iii) Two deferred minors: ADR 0002 §1 has no correspondence table
  to ADR 0001's section numbers; `BASELINE.md` rounds inherited P25 memory figures to 1 decimal where
  the source has 3 (`budgets.json` unaffected).
- **Review** clean, 0 Critical / 0 Important / 2 Minor.

### Task 1 — the engine module and its ticket contracts
- **SHA** `8e95356b` (`d8a30879..8e95356b`), additive only: the `:execution-runtime` module and the
  scheduler SPI.
- **Verified** all six contract signatures field-for-field against the plan's shared-contracts
  section, validation including nullable `queueDeadline` against non-null `notBefore`, `List.copyOf`
  defensive copies, and that the module exposes only the SPI with no new production dependency.
- **Command** `:control-plane:compileJava` plus the module's test task.
- **Evidence** RED observed first as 6 "cannot find symbol" errors, then `SchedulingTicketTest`
  **7/7**; `:control-plane:compileJava` green.
- **Limits** no engine, strategy or index implementation leaked in. One deferred minor: the test uses
  the fully-qualified `SchedulerSelection` name at three sites instead of an import.
- **Review** clean, 0 / 0 / 2 Minor. The reviewer's Minor on `SchedulerControl.java`'s javadoc was
  **overruled** with evidence (plan line 382, Task 5, settles the taxonomy the javadoc anticipates).

### Task 2 — the two existing algorithms preserved as ticket indexes
- **SHA** `07b152c8`, fix round to `eaecbc64` (`8e95356b..eaecbc64`).
- **Verified** that both strategies reproduce the old algorithms. Trace-compared against the **real**
  `Scheduler`/`QueueManager`/`FunctionQueueState` and the **real** `SyncQueueService` (package-private
  constructors, not mocks): per-function corpus `[a0,a1,a2,b0]` against the real loop, both yielding
  `[a0,a1,b0,a2]` — the turn moving after batch 2 — and shared-queue 65 blocked + 1 ready against the
  real service, both yielding `[NONE, ready]`. Constants `batch=2` and `scan=64` were **not** tuned,
  checked both ways by the re-reviewer.
- **Command** the module suites plus the trace-comparison tests.
- **Evidence** 4+4 tests GREEN after RED-for-missing-factory; the reviewer hand-traced both corpora
  independently and derived the same orders itself, confirming the comparison is not two sides
  derived from one source.
- **Limits** two structural narrowings carried to Task 4: `PerFunctionSchedulingStrategy.select`
  scans past a blocked function within one call where the old loop drops it and waits for a re-signal
  (justification pinned in the javadoc — drop-and-resignal is the engine's job, and Task 4 owns the
  wake sequence that makes it true); and `SyncScheduler.rotateReadyItem` (single-item rotation on
  lease failure) has no equivalent in the new index, so the engine must cover that case itself. Two
  deferred minors, both cosmetic.
- **Review** first pass NEEDS FIXES (2 Important: the plan-mandated corpus "publish, block, unblock,
  dispatch, removal" was not exercised, and the structural divergence was real). Both remedies were
  required and taken; re-review clean.

### Task 3 — authoritative pending store and queue reservations
- **SHA** `3d1620cf`, fix round to `cad3fa7b` (`eaecbc64..cad3fa7b`).
- **Verified** every mandated transition line-for-line against the brief, and — the load-bearing
  check — that `snapshotPending` excludes both `claimed` and `submitting` with no side effects on the
  counts, asserted rather than return-value-only.
- **Command** the module's test task.
- **Evidence** RED a genuine compile error for two missing classes, then `PendingWorkStoreTest`
  **14/14**; the rest of `:execution-runtime:test` unaffected.
- **Limits** two carried forward, both confirmed real by review: `remove()` on a submitting ticket
  returns null **by design**, so the store offers no handle to cancel an already-committed dispatch,
  and the engine must own that cancellation explicitly rather than assume it; and even with the guard
  the store cannot stop a caller claiming a submitting ticket in every ordering, so the engine must
  claim only tickets it knows are not submitting (call discipline). Three deferred minors.
- **Review** NEEDS FIXES (2 Important), both fixed in a fix round whose new tests were judged on
  whether they would fail if the defect returned, not on their names.

### Task 4 — the single engine loop and dispatch protocol
- **SHA** `665934a1`, fix round to `2764b939` (`cad3fa7b..2764b939`).
- **Verified** the engine loop: gate-held state, the wake sequence, the budgeted pass, worker
  start/stop, and the retry-publication split that ADR 0002's lock order requires.
- **Command** the module's test task.
- **Evidence** 3 Critical fixed and re-traced rather than accepted: worker replacement guarded by
  `worker == currentThread` on **all** exit paths (normal stop, caught `RuntimeException`, uncaught
  `Error`, `InterruptedException`); the three throw paths out of `SchedulerDispatchSupport` all
  leaving the result null so the `finally` takes the `finishSubmit` arm and the reservation is
  released, double-settlement impossible; and a lost-wake audit showing `wakeSequence` is mutated
  only inside the same critical section that notifies, with all four callers already under
  `synchronized(gate)`. Tests **11 → 15**, each new one checked to fail if its defect returned.
- **Limits** four deferred minors, two of them load-bearing later: (i) `blocked` is never cleared
  while the engine is busy — a delay, not starvation, and re-checkable once `signal()` has real
  production callers (which Task 8 gives it); (ii) the `claimSettled` guard **narrows** rather than
  closes the stranding window when `activeIndex.remove` throws after `store.commit`, unreachable with
  the two in-repo indexes but reachable for an SPI index; (iii) a **latent NPE** in
  `finishSubmit`/`requeue` — `deadlines.remove(ticket)` unguarded against the
  `Comparator.comparing(SchedulingTicket::queueDeadline)` comparator, safe today only because a pure
  profile makes the set empty or uniformly non-null, and **Task 8 creates exactly the mixed case**;
  made a binding requirement with a mandated regression, not left deferred; (iv) the new timeout
  test's comment states the wrong mechanism and one break stays unexercised. A trap for Task 5/6
  tests was also carried: with `running == true` and a frozen `nanoTime`, `await()` never returns.
- **Review** NEEDS FIXES (3 CRITICAL, 2 Important, 5 Minor), clean after one fix round.

### Task 5 — atomic index replacement under load
- **SHA** `8caf681f` (`2764b939..8caf681f`), +725/-25 across 3 files.
- **Verified** that the index swap is a genuine linearization point: the build happens outside the
  gate, the commit inside it, and nothing fallible follows.
- **Command** the module's test task.
- **Evidence** RED was 41 compile errors; GREEN `:execution-runtime:test` **48/48** with 13 new switch
  tests.
- **Limits** four deferred minors, none load-bearing: `carry`'s index-swap branch calls
  `active.index().add(ticket)` unguarded after `store.abort` (so the report's blanket "nothing is
  stranded on any path" overstates it); `discard()` and one pre-existing `log.warn` do logging under
  the gate, in nominal tension with the class's own "leaf lock, no I/O under the gate" doctrine; the
  in-flight-claim test verifies `lease.release()` was called but does not independently pin that it
  happens outside the gate; and the brief's literal "transition flag removed in a `finally`" was not
  built, the class javadoc instead stating "no separate flag: the gate itself is the flag".
- **Review** clean, no fix round.

### Task 6 — runtime-config commit coherent with an irreversible time change
- **SHA** `a87a7c12` (`8caf681f..a87a7c12`), 6 files, +496/-4.
- **Verified** the commit ordering — revision check → `validate()` → `prepare()` →
  `snapshotReplacing()` → `commit()` → `revision.set`, with no re-read of `registry.snapshot()` after
  the commit; and that `close()` is called on both the success and the failure path with the original
  failure propagated rather than replaced.
- **Command** the module's test task and `./gradlew build --no-parallel`.
- **Evidence** `RuntimeConfigServiceTest` **19/19** (10 legacy + 9 new, both mandated regressions);
  the sync-queue legacy apply/restore 4 tests still green; full build succeeds. The deviation from
  the brief's try-with-resources snippet is verified on every path it had to hold:
  `cleanupFailureAfterSuccessfulCommitDoesNotFailTheUpdate` stubs `doThrow` on `close()` and asserts
  the revision advanced, `commit()` ran, and nothing propagated.
- **Limits** four deferred minors (no `verify(ok2, never()).snapshot()` on the other-namespace
  failure; no combined pre-commit-failure-with-throwing-`close()` test; no
  `verify(change).close()` on a pure success path; `SchedulerRuntimeConfigExtension.apply()`
  unreachable through the service and undocumented). GitNexus produced a **third distinct failure
  mode** here: `detect-changes` omitted the three new files under both `--scope compare` and
  `--scope all` after a fresh reindex, while `context` showed the symbols correctly indexed.
- **Review** APPROVED, 0 / 0 / 4 Minor, no fix round. The deliberate deviation from the brief's
  snippet was accepted by ruling because the brief contradicts itself — line 19 declares `close()`
  cannot abort a commit, line 61 mandates a test in which cleanup *does* fail.

### Task 7 — the administrative API and OpenAPI contract
- **SHA** `5b9e360c` (`a87a7c12..5b9e360c`), 5 files, +506/-9.
- **Verified** that the diff touches no engine, service or SPI file; that the 503 rejection is
  genuinely pre-service (`supplyAsync`'s `execute()` throws on the calling thread, so
  `RejectedExecutionException` is caught in the controller and `applyPatch` is never entered); that
  422 and 400 are correctly separated; and that the Netty loop is never blocked.
- **Command** `./gradlew :control-plane-modules:runtime-config:test` and
  `:control-plane:composeControlPlaneOpenApi`, run three times.
- **Evidence** BUILD SUCCESSFUL with the results XML giving **total = 35, failures/errors = 0,
  skipped = 0**, both `SchedulerRuntimeConfigIntegrationTest` nested classes present in the results;
  `composeControlPlaneOpenApi` exits 0; module suite 35/35 across 3 reruns.
- **Limits** two deferred minors: the new `SchedulerNamespace` component schema in `openapi.yaml` is
  defined but never `$ref`'d, so `GET /{namespace}` still returns a generic object; and the 503
  description says "rolled back or never applied", which overstates the pre-commit path — nothing is
  ever applied to roll back, `close()` being a no-op by design. GitNexus failure mode #4 recurred: a
  new test file was omitted and an unrelated symbol included.
- **Review** clean, no fix round.

### Task 8 — single composition and profile compatibility
- **SHA** `81bedbeb`, three fix rounds to `f20cb234` (`5b9e360c..f20cb234`) — the largest task by file
  count (14) and the first to put both queue modules on one classpath.
- **Verified** the four-profile composition matrix (both / async-only / sync-only / none); the three
  NPE guards landed with a 3-case regression observed RED as a real NPE with a stack trace through
  `TreeSet.remove` and then GREEN; `EngineReadiness.runnable` implemented non-blocking and
  side-effect-free, which is what Task 4's "gate is a leaf" property depends on; and the capacity and
  scaling paths wired to `signal()`.
- **Command** the four-profile control-plane matrix plus the execution-runtime, async-queue,
  sync-queue, runtime-config and gradle-plugin suites.
- **Evidence** all **nine suites BUILD SUCCESSFUL** at `f20cb234`, run serially.
  `SchedulerEngineRemoveAllForGateDisciplineTest` was verified RED — ConcurrentModificationException,
  the finding predicted — then GREEN 3/3.
- **Limits** the autoscaler and concurrency governor were genuinely inactive from this task until
  Task 11, with two suites left red in between and the tests `@Disabled` by an explicit reason string
  rather than silently: re-enabling them, and restoring the two context assertions, was made binding
  for Task 11. Item 2 of round 3 was closed by widening an Awaitility window (2 s) rather than
  eliminating the cause, correctly disclosed. Task 8 also carries the forward-looking consequence of
  Task 4's latent NPE: composing both queues is what creates the mixed-deadline case.
- **Review** round 1: 3 CRITICAL + 6 Important + ~8 Minor; round 2 introduced and fixed 1 Critical +
  1 Important; round 3: 3 items. Clean after 3 fix rounds. One `NEEDS_CONTEXT` escalation ruled on.

### Task 9 — store, capacity and input extracted into the mandatory runtime
- **SHA** `fbadce46` (`f20cb234..fbadce46`), 56 files.
- **Verified**, by the controller before review, that `execution-runtime/build.gradle` depends on
  exactly `api project(':control-plane-spi')` + Caffeine + Micrometer (+ test-only); that the FQNs of
  `ExecutionStore`, `ExecutionLifecycle`, `FunctionCapacityRegistry`,
  `InvocationInputRejectedException` and `ExecutionStoreProperties` are preserved; and that zero
  duplicate copies of the three key classes remain in `control-plane/src/main`.
- **Command** `./gradlew :execution-runtime:test` and `./gradlew :control-plane:test`.
- **Evidence** BUILD SUCCESSFUL, exit 0; `control-plane:test` **690/690** across all four module
  profiles with **3 pre-existing skips**; `RuntimeArchitectureTest` RED→GREEN with its new rule; the
  `spring-configuration-metadata.json` concern did not materialise — all 14 `nanofaas.invocation-capacity.*`
  and all 6 `nanofaas.execution-store.*` keys survive.
- **Limits** (i) the graph's CRITICAL tier was trusted but its 282-entry impacted list was **not** —
  roughly half the rows had empty `filePath` and one pointed at an unrelated Rust experiment file, so
  the move was driven from a grep census instead; (ii) GitNexus failure mode #7 followed:
  `detect-changes` silently omitted 5 of 6 rename+modify files that had real content diffs; (iii)
  native AOT for the cross-jar `@ConfigurationProperties` bean remains unverified — it is genuinely
  Task 13's native gate; (iv) the downstream `:offload` and `:concurrency-control`
  `compileTestJava` regression introduced here was missed by the task's review **and** by the
  controller's own verification, and was found by Task 10's implementer — the standing correction is
  that every module depending on a changed one must have its suite run; (v) three further deferred
  minors, including Caffeine and Micrometer declared `implementation` while `ExecutionStore`'s and
  `IdempotencyStore`'s public constructors expose their types (an ABI leak that compiles only because
  `:control-plane` happens to declare both), carried to Task 10.
- **Review** APPROVED, 0 Critical / 0 Important / 6 Minor. The risky item — a pre-existing ArchUnit
  rule modified from "two modules" to "three" — was verified four ways as **extended, not weakened**
  (selector byte-identical, importer scope untouched, condition a strict superset, and the added
  disjunct a single literal path swallowing no package).

### Task 10 — attempts, retry and admission through the common engine
- **SHA** six commits, `fbadce46..0063a9aa` (4 sub-steps, 1b, and a fix round). Escalated BLOCKED
  with zero files touched before any edit — correct, and the four-way split it triggered is what
  surfaced the vacuous independence pin, the too-late `Assumptions` gate and the Task 9 ABI
  regression.
- **Verified** the attempt state machine, the wait estimator and sync admission moved into the
  runtime; `AttemptCoordinatorTest`; and that the fix round's assertions would fail if the defect
  returned (checked mechanically: `AttemptCoordinator.java` is byte-identical across the fix, so the
  falsifiability mutation was genuinely reverted).
- **Command** the module suites, plus the downstream `:offload` and `:concurrency-control` test
  compilation.
- **Evidence** the fix-round diff touches only the test file (+69); both new test methods present;
  suite green.
- **Limits** the strategy-switch axis of invariant #2 was accepted as **formally addressed** and its
  substantive pin **carried to Task 12**: the new test's assertions are real but structurally inert,
  because `SchedulerEngine`'s constructor takes no `FunctionCapacityRegistry` and the two subsystems
  share no reference in that fixture, so no defect inside `switchTo` can flip either assertion. The
  meaningful version needs the engine and the attempt path sharing a real registry, which Task 12's
  brief already mandates verbatim (`claim→switch→lease acquired`). Eight deferred minors.
- **Review** APPROVED, 0 Critical / 0 Important / 8 Minor, plus **one genuine missing requirement**
  (the brief-mandated strategy-switch conformity test), fixed in one round.

### Task 11 — shared observation, controllers and cleanup
- **SHA** `21266b22` (`0063a9aa..21266b22`), one fix round.
- **Verified** the switch observer placed in the outer `finally`, outside the gate and wrapped so the
  composition root cannot break the invariant; that the diff adds no fallible step after Task 5's
  linearization point; and — by tracing, not by claim — that the `sync_queue_depth` gauge is
  balanced: `onAdmitted` runs only after `engine.enqueue` succeeds, and the only three terminal paths
  out of the engine each call `settleIfSyncOrigin` exactly once, with double-settle structurally
  impossible because the pending set is a `Set` with an idempotent `remove`.
- **Command** the module suites.
- **Evidence** both `@Disabled` tests re-enabled and the two context assertions restored; the brief's
  **named acceptance test** written and verified deterministic — it parks the worker inside `submit()`
  via a latch entered only after claim/commit, and asserts all three brief properties separately:
  meters back to baseline, `reservedCount` to zero, and the still-claimed ticket holding
  `reservedCount == 1` while `onRemove` has fired.
- **Limits** three Important were real and all in code this task touches: `sync_queue_depth` was
  newly wrong rather than merely absent (nothing called `metrics.dequeued()` once its only callers,
  in the retired `SyncQueueService`, were gone — so the gauge published a cumulative admission count
  under a name that says depth); `WorkloadDiagnostics` was half-wired, registering 6 per-function and
  2 global meters that nothing records; and the brief's named acceptance test was missing. All three
  fixed. Seven deferred minors. The controller's own framing of concern 1 was **wrong** and the
  reviewer corrected it with evidence: the depth divergence is bounded at +1 globally, not N.
- **Review** NEEDS FIXES (0 Critical, 3 Important, 7 Minor), then all findings ADDRESSED with no new
  breakage.

### Task 12a / 12b — common suite, races
- **SHAs** 12a `9ff3f72d` + `52017320` (gap); 12b `9faa63b9` + `a90885c1` (gap); reviewed as one
  surface, `9faa63b9..a90885c1`.
- **Verified** the conformance matrix parameterised over **both real strategy implementations**, and
  the model test (seed `208L`, 10 000 operations) with the mandated attempts map, in which `complete`
  is a **real completion driven through the engine** rather than a no-op, and a failure inside the
  budget republishes through a real `RetryScheduler`. The eight mandated barrier-forced interleavings,
  with row (8) serialised by a latch that starts the committer thread only after the disconnecter's.
- **Command** the three module suites, run **serially** — a concurrent Gradle build in this checkout
  corrupted a previous verification pass through shared `build/test-results`.
- **Evidence** `:execution-runtime` **281/0**, `:control-plane` **696/0/4 skipped**,
  `:runtime-config` **35/0**; 342 completions and 198 retries in the model run.
  **Falsifiability, which is why this pass existed:** mutating `attempt <= maxRetries` to `<` gave
  BUILD SUCCESSFUL on the previous revision (blind) and FAILS at op 293 on this one, then reverts
  green; reversing row (8)'s order fails deterministically with
  `Expecting actual: ["commit","disconnect"] to contain exactly (and in same order): ["disconnect","commit"]`.
- **Limits** the retry-exhaustion (ERROR) branch is exercised once, at seed 208, because queued
  retries are withdrawn before a tick reaches them; the review adjudicated it a **pin** despite the
  single sample, because both directions of an off-by-one at the retry boundary are caught. Four
  deferred minors, including one non-vacuity asymmetry (nothing forces a SUCCESS value in
  `completedStateById`) and the fact that `containsExactly("disconnect","commit")` pins the test's
  own barrier rather than production behaviour.
- **Review** clean, 0 Critical / 0 Important / 4 Minor.

### Task 12c — the benchmark harness, the campaign, and the frozen-budget comparison
- **SHAs** harness `a7e7c47c`, the run `83522ee8`, corrective rounds `5ecd6def`, `90e0e4ac`,
  `3f352ee4`, `7f2c44c0`, `b766cc51`, `7e70f9c4`, `174a1a03`, `7beabc33`
  (range `a90885c1..7beabc33`, 10 commits, review clean after 5 fix rounds). Campaign artifacts carry
  `nanofaas.sha = a7e7c47c` **and** `harnessSha256 = f1941ab4…`, because the harness changed after
  that commit and the repository SHA alone would not say so.
- **What it answers:** what a **manual switch** costs. Its own sections above carry the round-by-round
  detail; `RESULTS.md` carries the tables and the command that produced each.
- **Command**
  ```bash
  ./run.sh --label=full                       # 260 samples, 1 140 switch events, the 1 000-switch phase
  python3 summarize.py raw/steady.jsonl       # the corrected-protocol tables and the resolving power
  python3 summarize.py raw/full.jsonl         # the round-1 tables and the mechanism rows
  ```
- **Evidence** `maxSwitchPauseMs` **3.984 ms** (budget 250) — `raw/full.jsonl`, max over 1 140
  measured switches, and that maximum is the process's **first** switch on an empty engine, the
  harshest available reading; `maxSwitchPauseP99Ms` **0.266 ms** (budget 100); `maxSwitchPreparationMs`
  **3.984 ms** (budget 2 000, an upper bound — total switch duration); `maxLiveStrategyIndexes` **2**
  (budget 2); the 1 000-switch return-to-baseline phase **1 000 committed, 0 refused**, pending
  **398 vs 398** and reservations delta **0** (`raw/baseline.jsonl`); work conserved in **240 of 240**
  runs. Host quiet throughout: load average 0.12–0.85 on 20 CPUs, no Gradle build in flight, no other
  sampling run.
- **Limits** the regression budgets resolve to **"no regression established at this campaign's
  resolution, which is coarser than the budgets"**: the arms' own median spread is **16.1 %** (steady
  p99) and **26.9 %** (thread CPU per useful completion) against budgets of 5 % and 10 %, so a
  uniform +10 % CPU regression — exactly the budget — would have been invisible in **24 of 24**
  comparisons (the CPU row's eligible count in the resolving-power table; +10 % detects 0 of them). The two over-budget rows (+9.51 % p99, +22.06 % CPU) remain over budget and are
  attributed to the instrument, not reported as regressions. Post-GC heap is the one metric with
  teeth — its arms agree to 0.0 %, so a uniform +5 % shift separates them in **24 of 24** comparisons
  — and it **passes**. Round 1's larger readings (+76.13 % p99
  on `capacity-change`, +23.83 % CPU) came from a protocol later found to pool two strategies and are
  superseded. The 1 000-switch phase here is **not** the ≥60-minute soak — that is Task 13.
- **Review** clean after 5 fix rounds, with every load-bearing claim reproduced by computation.

### Task 12e — the refactor's own cost, old loop against new engine
- **SHAs** arm and driver `c64da071`, campaign `9e380ca1`, corrective rounds `84cbb476`, `d78e5f51`,
  `4dc6b0f9`, `df8beb20`, `0d652898` (range `7beabc33..0d652898`).
- **What it answers:** what the **refactor itself** costs — the pre-refactor async loop against the
  new engine on the same algorithm. Its own section above carries the detail; `OLD-VS-NEW.md` is the
  deliverable and `raw/old-vs-new.jsonl` the artifact.
- **Command**
  ```bash
  ./run-old.sh                                  # the paired campaign, old arm vs new arm, one JVM
  python3 old-vs-new.py                         # the tables, importing summarize.py's settlement rule
  python3 reconcile-12e.py --perturbations      # the reconciler and its own demonstrated bite
  ```
  > **Tombstone (Task 13c, issue #208, 2026-09-22): the first command above no longer runs.**
  > Task 13b deleted the old loop (`modules.asyncqueue.Scheduler` and
  > `modules.syncqueue.scheduler.SyncScheduler`), so `OldLoopComparison.java`, which imports
  > `Scheduler`, cannot compile at this commit or after it. `run-old.sh` now aborts at its `javac`
  > step with a non-zero exit and writes no `raw/*.jsonl` — fail-closed, no half-artifact — and its
  > header says so; `OLD-VS-NEW.md` §7.1 carries the same tombstone. The other two commands still
  > run, because they read the committed `raw/old-vs-new.jsonl`. Re-measuring the old loop needs a
  > revision before Task 13b; `25388b1a` is the last one that compiles the harness.
- **Evidence** paired design — one arrival script per (workload, repetition), materialised before
  either arm runs and replayed to both. **60 runs, 60 of 60 conserving work**, zero driver failures,
  zero sample-cap overruns. The nulls and their resolutions: steady p99 **3.5 %** (median across
  workloads; `low-load`'s own is 9.36 %), whole-span p99 **5.4 %** (`unqueued`'s 11.41 %), useful
  throughput **0.01–0.03 %**, post-GC heap **0.01 %** with all six per-profile medians negative.
  `budgets.json`, `SchedulerSwitchBenchmark.java` and `summarize.py` unchanged.
- **Limits** (i) **allocation per useful completion is consistently heavier in the new engine** —
  5 of 5 same-signed on every non-confounded profile, per-profile medians **+5.5 % to +27.8 %**,
  repetition-level deltas **+1.59 % to +45.92 %**, window totals **+1.58 % to +45.84 %**; **no frozen
  budget covers allocation**, so this is a finding with no verdict, and the mechanism is **not**
  established by this harness and not guessed at. (ii) CPU per useful completion is **the metric the
  pairing did not sharpen**: its paired spread is **43.15 %**, worse than the 33.31 % unpaired, so the
  10 % budget sits below this design's resolution on it. Four MISS rows result; only
  `mixed-kind-retry` separates, at **+29.02 %** (range +17.96…+47.92, 5 of 5 positive, every
  repetition above budget). (iii) One budget MISS is reported as a MISS: `low-load`'s steady p99
  **+5.40 %**, in absolute terms 2.570 ms → 2.793 ms against a 100 ms contract, with the five paired
  differences straddling zero. (iv) `saturated` is huge and **established but is not a loop result** —
  77 % of its offers expire on the engine and none on the old loop, which has no deadline; it is the
  **expiry policy** (M3), chosen and declared rather than equalised. (v) **the sync arm is not
  attempted** — M8 makes it comparable only when `syncQueueMaxQueueWait == contractMs` per profile,
  and nothing in this step speaks for the shared-queue side. (vi) **`head-of-line-blocking` is
  excluded** because `EngineReadiness` does not exist at the pre-refactor revision
  (`git grep -l EngineReadiness -- '*.java'` returns 0 files at `05f49dcb`, 15 at HEAD), so driving it
  on the old arm would have had 5 of its 6 functions dispatch work the engine refuses — a policy
  difference in the old arm's favour, not a loop one. `hot-plus-500-sporadic` (M2), `heterogeneous-burst`,
  `capacity-change` and `switch-under-load` are refused loudly by the harness because their driver
  features are not implemented. `churn-drain`'s two window metrics are NOT MEASURABLE.
- **Review** clean after 5 fix rounds, the last adjudicated at the cap rather than dispatched again.

### Task 12d — this entry, and `TASK12.md`
- **SHA** the commit carrying this section.
- **Verified** the two suite commands the Task 12 brief mandates, re-run with `--rerun-tasks` and
  serially, reading the per-module counts from the JUnit results XML rather than from an aggregate;
  the GitNexus audit over the Task 12 range with the index regenerated first; and the coherence of
  the figures in `RESULTS.md` and `OLD-VS-NEW.md` against their artifacts.
- **Command**
  ```bash
  ./gradlew :execution-runtime:test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain --rerun-tasks
  ./gradlew :control-plane:test :control-plane-modules:runtime-config:test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain --rerun-tasks
  node .gitnexus/run.cjs analyze --index-only
  node .gitnexus/run.cjs detect-changes --scope compare --base-ref 21266b22 --repo nanofaas
  ```
- **Evidence** both builds **BUILD SUCCESSFUL**; per module `:execution-runtime` **281 tests / 0
  failures / 0 errors / 0 skipped** (36 suites), `:control-plane` **696 / 0 / 0 / 4 skipped**
  (116 suites), `:control-plane-modules:runtime-config` **35 / 0 / 0 / 0** (7 suites) — matching the
  Task 12ab counts exactly. The Task 9 entry's `:control-plane` reading of 690 with 3 skips and this
  reading of 696 with 4 are different revisions, not a contradiction — tasks 10 and 11 added tests
  in between; the four skips are 2 in `CoreOnlyApiTest`, 1 in `P07ConfiguredHttpCalibrationTest` and
  1 in `OpenApiRouteCoverageTest`. The audit reports **51 files, 315 symbols, 816 affected processes,
  risk level CRITICAL**.
- **Limits** the CRITICAL tier is **not** evidence of production risk on this range, and the reason
  is checkable rather than asserted. (i) `git diff --name-only 21266b22..HEAD | grep src/main`
  returns **0**: nothing in production source changed in the whole range. (ii) The graph indexes
  **447 nodes** under `docs/experiments/scheduler-switching-2026-09/` alone (126 Property, 90
  Section, 86 Function, 48 Variable, 40 File, 22 Method, 16 Constructor, 15 Class), and the changed
  symbols the tool renders are drawn from that population: documentation `Section` nodes from
  `RESULTS.md` and `OLD-VS-NEW.md`, and standalone-harness symbols — `MeasuredRun`, `Run`, `Attempt`
  and `TicketId` are each whole words in the harness sources (13, 28, 15 and 17 occurrences). (iii)
  The ordinary names the tool uses for symbols are not qualified by file, which is what lets a
  generic bare name on a documentation or harness symbol match unrelated code; the specific
  collisions were not reconstructed here because the CLI truncates its own rendering (15 symbols, 10
  flows, even with `--limit 10000`) and the graph is degraded this run. (iv) The reported "affected
  flows" — `Main → Attempt`, `Churn → AdmitsNewWork`, `RunT2 → Tag` — all list the **same single
  changed step**, the harness method `MeasuredRun`, which is what a name-based attribution looks
  like rather than a production call path. (v) The graph's own condition supports no stronger claim:
  the FTS index build failed (`File.file_fts`), process discovery reported whole flows missing
  (2 020 of 2 220 candidate entry points never ranked), and direct `impact` queries on the two
  symbols Task 0 recorded as CRITICAL returned `UNKNOWN` (empty walk) for `FunctionCapacityRegistry`
  and `ambiguous` over 13 same-name candidates for `ExecutionStore`. The tier is a **recurrence of
  the third distinct failure mode** in the campaign's running tally — a confident CRITICAL raised
  from bare-name collisions — not a new one; the full-diff reviews remain the control that has
  carried the weight.
- **Still open, and not this task's to close** the ledger ruling that the gitignored ledger and task
  reports be force-added to git before the workspace is deleted has **not** been executed. Until it
  is, this section and `TASK12.md` are the durable record and the ledger is the richer one.
