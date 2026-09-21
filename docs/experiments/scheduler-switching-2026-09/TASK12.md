# Task 12 — the consuntivo

**What this is.** Task 12 of the plan for [issue #208](https://github.com/miciav/nanofaas/issues/208)
(manual scheduler switching) is the task that measures whether the new scheduler engine is safe and
fast enough to ship. The plan puts it plainly: if the measured switch pause exceeds the frozen
budgets, "questa implementazione non supera il gate e va corretta prima della consegna". It was split
into five steps so that no single pass had to carry a conformance suite, a race suite, a benchmark
campaign and a second benchmark campaign at once.

**Vocabolario minimo, for a reader who has not followed the process.**

- *scheduler switch* — an operator asks the running control plane to change from one scheduling
  algorithm to the other (`per-function` ↔ `shared-queue`) without restarting it and without losing
  or reordering in-flight work.
- *budget* — a threshold frozen before the work started, in `budgets.json`. It has not been modified
  by any step of Task 12, or by anything else.
- *arm* — one of the two configurations being compared. In Task 12c the arms are the two strategies
  inside the new engine; in Task 12e the arms are the **old loop** and the **new engine**.
- *resolution* (or *resolving power*) — the smallest real difference a measurement could have
  detected. A null result from an instrument that cannot see a 10 % change says nothing about 10 %
  changes, and every null in this document is reported next to its own resolution for that reason.
- *paired / unpaired* — unpaired designs compare two groups measured at different times, so any drift
  between the runs lands in the result. A paired design replays the **same** input sequence to both
  arms, so the difference is computed per repetition and the drift cancels. Task 12c is unpaired;
  Task 12e is paired.

**The single most important structural fact about this consuntivo.** Task 12 measures **two different
things**, and fusing them is how a previous null result in this campaign was read as stronger than it
was:

- **Question A — what does a manual switch cost?** Task 12c, the benchmark harness and the campaign.
  Its subject is the switch itself.
- **Question B — what does the refactor itself cost?** Task 12e, the old loop against the new engine
  on the same algorithm. Its subject is the change of engine.

They answer different questions, from different designs, with different resolving power. They are
presented separately below, and no figure from one is used to support the other.

---

## 1. The five steps

Task 12 was split into four steps plus this one. A fifth piece of work, labelled **12ab**, was a
gap-fix pass dispatched after a resume audit found one load-bearing gap in 12a and one in 12b; it is
part of 12a/12b's review surface, not a separate step.

| step | commits | what it produced |
|---|---|---|
| **12a** | `9ff3f72d`, and the gap fix `52017320` | `SchedulerConformanceTest.java` — the conformance matrix, parameterised over **both real strategy implementations** — and `SchedulerModelTest.java`, the model test: seed `208L`, 10 000 randomised operations, a reference model carrying the attempts map, and `complete` wired as a **real** completion driven through the engine rather than a remove-no-op. Covered 342 completions and 198 retries. |
| **12b** | `9faa63b9`, and the gap fix `a90885c1` | `SchedulerSwitchRaceTest.java` — the eight barrier-forced interleavings the brief mandates, including `claim→switch→lease acquired` over a genuine shared `FunctionCapacityRegistry`, and row (8)'s client-disconnect serialised ahead of the commit by a latch rather than asserted order-independent. |
| **12c** | harness `a7e7c47c`, the run `83522ee8`, then the corrective rounds `5ecd6def`, `90e0e4ac`, `3f352ee4`, `7f2c44c0`, `b766cc51`, `7e70f9c4`, `174a1a03`, `7beabc33` | `SchedulerSwitchBenchmark.java` (2 380 lines as committed; it was 2 132 at the harness commit `a7e7c47c` and grew over the corrective rounds), `run.sh`, `summarize.py`, `ClockTest.java`, the `printTestClasspath` Gradle task, `raw/*.jsonl` and `RESULTS.md`. **Answers Question A.** |
| **12e** | arm and driver `c64da071`, campaign `9e380ca1`, then the corrective rounds `84cbb476`, `d78e5f51`, `4dc6b0f9`, `df8beb20`, `0d652898` | `OldLoopComparison.java`, `run-old.sh`, `old-vs-new.py`, `reconcile-12e.py`, `raw/old-vs-new.jsonl`, `raw/reconciliation-12e.txt` and `OLD-VS-NEW.md`. **Answers Question B.** |
| **12d** | the commit carrying this file | The verification sweep, the GitNexus audit, the consolidated `STATO.md` entry for tasks 0-12, and this consuntivo. No measurement of its own. |

Provenance, stated because it is easy to get wrong: the 12c and 12e artifacts record **a repository
SHA and a harness digest**, not just a SHA, because both harnesses changed after the commits that
introduced them. In 12c the pair is `nanofaas.sha = a7e7c47c` with
`harnessSha256 = f1941ab4…`; in 12e `raw/old-vs-new.jsonl` records revision `c64da071` with harness
digest `76190891…` and committed-harness digest `353ee278…`. The digest identifies the build; the
revision records where the tree was.

---

## 2. Question A — what does a manual switch cost?

**Answered by Task 12c, against the frozen budgets in `budgets.json`. The switch mechanism passes
decisively.**

| budget | frozen | observed | verdict |
|---|---|---|---|
| `maxSwitchPauseMs` | 250 ms | **3.984 ms** — the maximum over 1 140 measured switches | PASS |
| `maxSwitchPauseP99Ms` | 100 ms | **0.266 ms** — p99 over the 1 000-switch phase | PASS |
| `maxSwitchPreparationMs` | 2 000 ms | **3.984 ms** — total switch duration, an upper bound on preparation | PASS |
| `maxLiveStrategyIndexes` | 2 | **2** | PASS |
| `switchesInSoak` | 1 000 | **1 000 committed, 0 refused** | PASS |
| `maxPostGcHeapRegressionPercent` | 10 % | **+0.01 %** — the arms' own spread is 0.0 % | PASS |
| `maxUsefulThroughputRegressionPercent` | 5 % | **+2.09 %** — the worst of the 22 measurable comparisons | PASS |
| `maxSteadyP99RegressionPercent` | 5 % | worst **+9.51 %** | over budget; see below |
| `maxCpuPerCompletionRegressionPercent` | 10 % | worst **+22.06 %** | over budget; see below |

**The pause figure is the harshest available reading.** 3.984 ms is the maximum over 1 140 switches,
and that maximum is the **first** switch of the process, on an empty engine with a cold JIT. Against
250 ms it clears the budget by roughly sixty times; against the 100 ms p99 contract the measured p99
clears it by roughly 375 times. Work was conserved in **240 of 240** runs (admitted = completed +
expired + removed + rejected + pending + claimed + submitting + in flight). The 1 000-switch
return-to-baseline phase ends with pending **398 vs 398** and a reservations delta of **0**.

**Declared resolving power for this question — and it is the reason the two over-budget rows are not
called regressions.** The design is **unpaired**: the arms were measured in separate runs, so any
drift between runs lands in the comparison. The arms' own median spread across five repetitions is
**16.1 %** for steady p99 and **26.9 %** for thread CPU per useful completion, against budgets of
**5 %** and **10 %**. The criterion is therefore *coarser than the budget it is testing*: for thread
CPU per useful completion — the metric the 10 % budget names, and the one with 24 eligible
comparisons — a uniform **+10 % regression, exactly the budget, would have been invisible in 24 of
24 comparisons** (the resolving-power table's CPU row detects 0 of its 24 at +10 %).
The honest statement of the regression result is not "no regression" but:

> **No regression is established at this campaign's resolution, and that resolution is coarser than
> the budgets.**

The one metric with real teeth was post-GC heap, whose arms agree to a **0.0 %** spread, so even a
uniform +5 % shift would separate them in **24 of 24** comparisons. The plan's own disposition for
this case applies and was applied: differences within
the dispersion are declared non-distinguishable, **both schedulers are kept, no threshold was moved,
and no window was picked to pass**. Round 1's larger readings — +76.13 % p99 on `capacity-change` and
+23.83 % CPU — came from a protocol later found to pool two strategies into one arm, and are
superseded; the `capacity-change` p99 row was a **settling transient**, not the switch mechanism: that
arm enters its window carrying the shared-queue phase's deeper standing queue, the whole distribution
is shifted rather than only the tail, and the difference decays to +0.6 % with overlapping ranges.

---

## 3. Question B — what does the refactor itself cost?

**Answered by Task 12e, with a paired design that fixes exactly the weakness of Question A's
measurement.** `OldLoopComparison.java` drives the pre-refactor async loop (`Scheduler` over
`QueueManager`/`FunctionQueueState`) as one arm and the new engine with `PerFunctionSchedulingStrategy`
— the port of that same loop — as the other, **in one JVM, with one shared driver, alternating
repetition by repetition**. One arrival script per (workload, repetition) is materialised before
either arm runs and replayed to both, so the arms are offered the identical sequence and each
per-repetition difference is paired. Coverage: **6 profiles × 2 arms × 5 repetitions = 60 runs, 60 of
60 conserving work**, zero driver failures, zero sample-cap overruns.

**Declared resolving power.** The pairing cut the noise roughly fivefold: p99 resolves to a median of
**3.5 %** steady and **5.4 %** whole-span, against Task 12c's 16 %. That is why this null means
something where 12c's did not. Two caveats sit on the number and are stated with it: the headline
3.5 % / 5.4 % are **medians across workloads**, and a median is pulled down by profiles whose paired
differences already agree in sign; the per-workload figures are the ones that matter, and the
artifact carries them (`low-load`'s steady p99 resolves to **9.36 %**, `unqueued`'s whole-span p99 to
**11.41 %**, against `queued`'s 33.49 % and 43.48 %).

**The nulls, with their resolutions.**

- **Steady p99, useful throughput, post-GC heap: no regression established on any expiry-clean
  profile.** Useful throughput resolves to **0.01–0.03 %** paired — §8's median, the figure that
  section's own headline sentence introduces. §9.1 labels the same metric differently: its headline
  range for useful throughput is **0.00–0.05 %**, and it then gives the two columns separately
  (0.01–0.02 % whole-span; 0.00, 0.00, n/a and 0.05 % steady), which is why the union of the two is
  not quoted here. Post-GC heap resolves to **0.01 %**, with
  every per-profile median *negative* (the new engine's heap is marginally the lower of the two) —
  five of the six profiles are also same-signed, `low-load` alone having a range that crosses zero.
- **One budget MISS, reported as a MISS and not excused:** `low-load`'s steady p99 at **+5.40 %**
  (2.570 ms → 2.793 ms against a 100 ms contract). Its five paired differences run −19.52 % to
  +9.36 % — they straddle zero, and 5.40 % is below that workload's own 9.36 % resolution.

**The one consistent direction, and it is a finding the campaign did not have.** The new engine
**allocates more per useful completion**, in every repetition of every expiration-clean profile and of
`queued` too: per-profile medians **+5.5 % to +27.8 %**, repetition-level deltas **+1.59 % to
+45.92 %**, window totals **+1.58 % to +45.84 %** — 5 of 5 same-signed on each, and the direction
holds on the four genuinely clean profiles without `queued`. **No frozen budget covers allocation, so
there is no verdict.** The direction is consistent with the named hypothesis (the old loop amortises
one visit over up to two dispatches; the engine's pass claims one), but the mechanism is **not**
established by this harness and `OLD-VS-NEW.md` does not guess at it.

**CPU per useful completion is the metric the pairing did not sharpen.** Its paired spread is
**43.15 %**, *worse* than the 33.31 % unpaired spread — so its noise is intra-run JVM activity, not
host drift, and the 10 % budget sits **below this design's resolution on that metric**. Four MISS rows
result, three of them with the five repetitions on both sides of zero (not resolved). The exception
separates: **`mixed-kind-retry` at +29.02 %** (range +17.96…+47.92, 5 of 5 positive, every repetition
above budget), corroborated by the window-total CPU cross-check. **On that one profile the cost is
measured; elsewhere the direction is suggested and not separated.**

**One profile is established, huge, and is not a loop result.** `saturated`: 77 % of its offers are
expired by the engine and none by the old loop, which has no deadline — the old loop's 512-ticket
queue stays permanently full of work already past contract, refuses 14 149 fresh offers at admission,
and yields 34 useful completions against the engine's 3 655. That is the **expiry policy** (mismatch
M3), which this comparison chose and declared not to equalise, not the claim cadence. The document
attributes none of it to the loop. `queued` is the cautionary example the design exists for: a
single-repetition diagnostic showed the old loop 68.5 % *worse* on p99; across five repetitions it is
25.5 % worse with one repetition at +33 %, and the range crosses zero, so nothing is established.

---

## 4. What these measurements do NOT establish

Stated explicitly, because each of these is a way the results above could be read as more than they
are.

1. **The two questions are not one question.** Question A's null is weak (unpaired, 16.1 % / 26.9 %
   spread against 5 % / 10 % budgets) and Question B's null is comparatively strong (paired, 3.5 %
   resolution). Quoting B's resolution to support A's null, or reading A and B together as one
   "no regression" claim, is wrong. They measure different things.
2. **Task 12c's criterion is coarser than the budgets it tests.** A uniform +10 % CPU regression was
   invisible in **24 of 24** eligible comparisons (the CPU row's own count). Nothing in 12c can
   exclude a regression of the size the budgets name.
3. **Task 12e's CPU metric has a 43 % paired spread**, so its own 10 % budget sits below its
   resolution. Only `mixed-kind-retry` separates on CPU; the other four MISS rows are not resolved in
   either direction.
4. **The sync arm is not measured.** Task 12c's figures are for the new engine and the two strategies
   inside it; Task 12e's second arm is the old **async** loop (`Scheduler`/`QueueManager`). The old
   `SyncScheduler`/`SyncQueueService` arm was not attempted, because M8 makes it comparable only when
   `syncQueueMaxQueueWait == contractMs` per profile. **Nothing here speaks for the old shared-queue
   code path.**
5. **`head-of-line-blocking` is excluded, and the reason is a fact about the old tree, not a
   preference.** That profile is defined by `notReadyCount = 5`, and the gate it exercises is
   `EngineReadiness` — which **does not exist at the pre-refactor revision** (`git grep -l
   EngineReadiness -- '*.java'` returns 0 files at `05f49dcb` and 15 at HEAD). Driving it on the old
   arm would have had 5 of its 6 functions dispatch work the engine refuses: a policy difference in
   the old arm's favour, not a loop one. It is a miscategorised inclusion in the brief, not a skipped
   profile.
6. **Four further profiles are refused rather than approximated:** `hot-plus-500-sporadic` (M2 — no
   alignment exists), `heterogeneous-burst`, `capacity-change` and `switch-under-load`. The harness
   **refuses them loudly** rather than running a workload whose note describes something that did not
   happen. `churn-drain`'s two window metrics are NOT MEASURABLE.
7. **The soak belongs to Task 13.** The "1 000 switches" figure above is a 5-second return-to-baseline
   phase inside the benchmark. It is **not** the ≥60-minute soak the plan requires, and no soak was
   run in Task 12.
8. **The HTTP and NanoLab layers belong to Task 13.** Everything measured here is in-process, against
   the real engine, strategies, registry and pending store — but with no HTTP surface, no
   `PATCH`-driven switch through the admin API, and no NanoLab E2E. **Task 12 claims no Level-2
   coverage.** `SchedulerSwitchHttpTest` does not exist yet.
9. **The regression baselines are thinner than they look.** The paired requalification soak inherited
   from the lifecycle-memory campaign (P25) was never run, so if a memory or latency regression is
   observed later it cannot be attributed cleanly between this scheduler work and the still-unqualified
   candidate revision it builds on. See `STATO.md`'s Task 0 entry.
10. **One instrument limit, declared rather than closed.** Task 12e's reconciler checks the document
    against the **artifact**; nothing checks the reconciler's account of **itself**. Five rounds of
    hardening closed the artifact-facing side to zero residue (797 table cells and 146 prose claims
    recomputed at 0 mismatches, 12 of 12 perturbations caught, byte-identical regeneration), while §12
    — which describes the instrument — remains checked by reading. That class is declared in
    `OLD-VS-NEW.md`, not closed.

---

## 5. Carried forward: deferred minors and parked findings

These are the items the 12c and 12e fix rounds deliberately did **not** close. Both tasks hit the
campaign's fix-round cap; the plan's designed mechanism for the remainder is the **final whole-branch
review's fix wave**, which is where they now sit. None of them affects a measured figure or a budget
verdict.

**From Task 12c (never entered the fix loop):**

- The steady-throughput denominator undercounts admissions in the last ~contract of the window.
  Symmetric across arms, so it does not bias the comparison; deliberately deferred.
- `RESULTS.md:928` uses the bare `git diff 90e0e4ac -- …` citation form. It asserts no count and
  reproduces while the tree equals HEAD, so no action was taken — but it is the same footgun class as
  the citation that was fixed, and the final review should judge it.
- The 12c report carries two round numberings by design (section headings "Fix round k/5" against
  canonical "round n"), with the mapping stated in `STATO.md` and at the head of the report's round-7
  section.

**From Task 12c, parked with a ruling rather than sent around again** — all confined to the gitignored
report, no tracked claim, number or table affected: one `[OVERTAKEN …]` marker attributes a retraction
to round 5 where canonically it was **round 4**; "Three sites" appears where four markers exist; and
one sentence mixes the fix-wave label with the canonical numbering.

**From Task 12e, adjudicated at the cap (three findings, all one line or one sentence):**

1. `OLD-VS-NEW.md` §12 claims the instrument registry "recomputes every count below from the run".
   That is **false for five cells of its own coverage table** — they are correct today, but unguarded,
   and the claim is false as written. The ruling is to **narrow the claim** rather than armour it:
   say that §12's coverage table is its own account and is checked by reading, and add that to the
   document's declared limits, where it currently appears only in the report.
2. `reconcile-12e.py:1616`, the docstring of `self_test()`, says "a broken environment reports its
   eleven caughts" while `PERTURBATIONS` holds **12**.
3. That §12's prose sits outside the word pass is declared in the report but **not in the document**,
   so a reader of `OLD-VS-NEW.md` alone is not told. One clause in the limits.

A structural lesson worth carrying to the branch review, because it is general rather than local: the
instrument guards the document against the artifact, but nothing guards the instrument's account of
itself. Five rounds closed the artifact-facing side to near-perfection while the self-describing side
stayed open — because the thing doing the checking is the thing that would have to check itself.

---

## 6. What Task 13 still owes

Listed so that nothing above is read as covering it. **Task 13 has not started.**

- `SchedulerSwitchHttpTest` — the HTTP-level switch (a held invocation, ≥2 pending, `PATCH` changes
  strategy, IDs and results unchanged, then the reverse switch; sync profile still 501).
- Configuration tests: `NANOFAAS_SCHEDULER_STRATEGY`, unknown-strategy startup refusal,
  `PATCH`-then-restart.
- The strategy knob in `application.yml`, Helm `values.yaml`, `control-plane-deployment.yaml` and
  Compose.
- Native-image and JVM artefact with both strategies.
- NanoLab E2E, with no provisioning inside NanoFaaS.
- **The ≥60-minute soak.**
- Removal of the superseded wiring (`Scheduler.java`, `SyncScheduler.java`) once consumers are
  migrated.
- `NANOLAB.md` and `FINAL.md`.

---

## 7. Verification of this document

The two suite commands the Task 12 brief mandates were re-run at Task 12d with `--rerun-tasks`, one
after the other (concurrent Gradle builds share `build/test-results` and overwrite each other — that
already produced a false "flaky" finding once in this campaign). Counts were read from the JUnit
results XML, not from an aggregate line:

```bash
./gradlew :execution-runtime:test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain --rerun-tasks
./gradlew :control-plane:test :control-plane-modules:runtime-config:test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain --rerun-tasks
```

| module | suites | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| `:execution-runtime` | 36 | 281 | 0 | 0 | 0 |
| `:control-plane` | 116 | 696 | 0 | 0 | 4 |
| `:control-plane-modules:runtime-config` | 7 | 35 | 0 | 0 | 0 |

Both builds: **BUILD SUCCESSFUL**. The four skips are 2 in `CoreOnlyApiTest`, 1 in
`P07ConfiguredHttpCalibrationTest` and 1 in `OpenApiRouteCoverageTest` — pre-existing module-gated
assumption aborts, not failures.

The GitNexus audit over the Task 12 range (`21266b22..HEAD`, index regenerated first) reports **51
files, 315 symbols, 816 affected processes, risk level CRITICAL**. The tier is **not** evidence of
production risk here, and the reason is checkable rather than asserted:
`git diff --name-only 21266b22..HEAD | grep src/main` returns **0** — no production source file
changed in the whole range. The only files under `platform/` are one `build.gradle` (the
`printTestClasspath` task) and four test files, three of them new. The changed symbols the tool
renders are documentation `Section` nodes (`RESULTS.md`, `OLD-VS-NEW.md`) and standalone-harness
symbols in `docs/experiments/**/*.java` — `MeasuredRun`, `Run`, `Attempt` and `TicketId` are each
whole words in the harness sources (13, 28, 15 and 17 occurrences) — and the ordinary names it uses
for symbols are unqualified by file, which is what lets a generic bare name on documentation or
harness code match unrelated code. The specific collisions were not reconstructed, because the CLI
truncates its own rendering (15 symbols, 10 flows, even with `--limit 10000`). The reported
"affected execution flows" — `Main → Attempt`, `Churn → AdmitsNewWork`, `RunT2 → Tag` — all list
the **same single changed step**, the harness method `MeasuredRun`, which is what a name-based
attribution looks like rather than a production call path. The graph itself is degraded on this run:
the FTS build failed and process discovery reported whole flows missing (2 020 of 2 220 candidate entry
points never ranked). This is a recurrence of the campaign's third distinct graph-audit failure mode —
a confident CRITICAL raised from bare-name collisions — not a new one.

---

## 8. Reference

- **Issue:** [#208 — manual scheduler switching](https://github.com/miciav/nanofaas/issues/208)
- **Plan:** `docs/superpowers/plans/2026-09-16-manual-scheduler-switching.md` (Task 12 at line 738,
  Task 13 at line 795)
- **Contract:** `docs/architecture/0002-manual-scheduler-switching.md`
- **Frozen thresholds:** `budgets.json` (unmodified by every step of Task 12)
- **Campaign status:** `STATO.md`, including the consolidated entry for tasks 0-12
- **Question A:** `RESULTS.md` and `raw/*.jsonl`
- **Question B:** `OLD-VS-NEW.md`, `raw/old-vs-new.jsonl`, `raw/reconciliation-12e.txt`
