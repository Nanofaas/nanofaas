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
| `maxUsefulThroughputRegressionPercent` | 5 % | +4.11 % | PASS |
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
