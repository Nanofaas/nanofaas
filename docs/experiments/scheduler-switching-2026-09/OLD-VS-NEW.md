# The refactor's own cost: the old async loop against the new engine

Task 12e, issue #208. The comparison the specification's §11 asks the benchmarks to produce —
*«verificano regressioni rispetto alle implementazioni precedenti»* — and that did not exist,
because Task 0 froze thresholds and a revision but no figures (`BASELINE.md:155`), and because
Task 12c's harness can only swap strategies *inside* one engine (`spec:315`).

**This is a profile comparison — level 2 of the spec's taxonomy, not level 1.** The spec defines
level 1 as one engine with only the strategy exchanged (`spec:315`); `spec:316` says of
engine-versus-engine that «Questo misura un profilo, non attribuisce il risultato al solo
algoritmo». A ruling earlier in this campaign cited level 1 for this comparison and was corrected
in the ledger; nothing here may be read as satisfying level 1.

---

## 1. The hypothesis, named before the measurement

The old async loop dispatches **up to two attempts back to back per function visit**
(`Scheduler.java:31` `DEFAULT_MAX_BATCH_PER_FUNCTION = 2`, consumed at `:208`), while the new engine
takes **exactly one claim per pass** (`SchedulerEngine.selectAndClaim` → one `carry`). That is the
loop-layer difference the refactor introduced, and it is the subject of this measurement — not a
defect in either implementation and not to be discounted as noise.

Nothing here measures the switch. The old loop has no `switchTo`, so switch cost and switch pause
remain Task 12c's, and a document that claimed to cover both would be quoting a number it cannot
produce.

## 2. The two arms, and why they run in one process

| | old arm | new arm |
|---|---|---|
| loop | `Scheduler` over `QueueManager`/`FunctionQueueState` | `SchedulerEngine` |
| strategy | — (the loop *is* the policy) | `PerFunctionSchedulingStrategy`, the port of that same loop |
| dispatch seat | `InvocationDispatch` | `EngineDispatch` |
| arm label | `old-async (no change)` | `per-function (no change)` |

Each arm has **one** dispatch path, its own, taking the place of the transport/lifecycle on the far
side of that interface — the seat the real adapter occupies, not a second path beside it. The old
loop is never re-wired into the engine: a re-wired old loop would acquire leases from a relocated
`FunctionCapacityRegistry` and dispatch into a transport that Tasks 9–10 rewrote, a configuration
that exists in no revision, and it could produce no switch figure anyway.

CPU and post-GC heap are process-wide and attribute to no scheduler (M11). A cross-JVM comparison
of them measures the JVMs, so **both arms run in the same JVM**, alternating repetition by
repetition, with the same heap (`-Xms1g -Xmx1g -XX:+AlwaysPreTouch`), the same thread count (one
scheduler/worker thread per arm plus one shared driver thread) and the same allocation profile.
Arm order alternates with the repetition parity, so a monotone host drift cannot land on one arm.

## 3. What is shared, and therefore not a variable

The harness is compiled together with the committed `SchedulerSwitchBenchmark.java` and is in its
package, so:

- **the corpus is `SchedulerSwitchBenchmark.Profiles` itself** — not a copy. The profile values,
  their derived rates, service durations, payload sizes, churn and readiness sets and the
  hot/sporadic function draws are read from the committed code. A restated corpus is a second thing
  that can drift from the one being compared, and this campaign has been burned by exactly that.
- **the span, warm-up, depth-sampling cadence and trailing-window grid are its constants**
  (`SPAN_MS`, `WARMUP_MS`, `DEPTH_SAMPLE_MS`, `TRAILING_WINDOWS_MS`).
- **the JSONL schema is its schema**, with its field names, so `summarize.py` reads this artifact
  unchanged — §7 quotes `summarize.workload_table` run on `raw/old-vs-new.jsonl`.
- **the settlement rule and the "shift the control arm's own values" operation are `summarize.py`'s**,
  imported by `old-vs-new.py` (`settled_depth`, `settle_millis`, `settled_band`, `median`,
  `percent_delta`, `STEADY_WINDOW_MS`). `summarize.py` is not modified.

One arrival script per (workload, repetition) is materialised before either arm runs and replayed to
both, so the two arms are offered the **identical sequence**, not merely the same rate. The
committed harness drives each arm from its own `Random` and says in its own comment that its arms
therefore share a rate but not an arrival sequence — that is the difference this design removes,
and §8 shows what it is worth.

The service model is the committed harness's: `submit`/`dispatch` publishes a due time and returns,
the capacity lease is held until the driver judges the service duration elapsed, and only then
released. The committed precedent that busy-waits *inside* the dispatch callback
(`control-plane-tuning-2026-09/bench/T2BatchBench.java:97`) is not copied.

## 4. The driver recipe, and the one seam

The in-tree precedent (`PerFunctionSchedulingStrategyTraceComparisonTest`) builds
`QueueManager`/`FunctionQueueState`/`Scheduler`, admits a corpus and releases the lease it receives.
A measurement driver differs from it in exactly three things, all of which this harness adds:

1. **it holds each lease for the function's declared service duration** instead of releasing it
   immediately, so a slot is genuinely occupied and work that cannot get one genuinely waits;
2. **it decides the expiry question explicitly** — see M3, §5.2 — and its decision here is to add
   no reaper;
3. **it emits the JSONL schema**, with the committed field names.

It also reaps nothing itself, signals nothing itself for the old arm, and never touches the engine's
gate on the measurement path: the queue-depth trajectory comes from the driver's own counters
(`admitted − completed − expired − removed − rejected`), not from `snapshotQueues()`.

**The single seam: one reflected constructor.** `QueueManager`'s constructor is package-private in
`it.unimib.datai.nanofaas.modules.asyncqueue`, and this class must be in
`SchedulerSwitchBenchmark`'s package to reuse the corpus. `newQueueManager` reaches exactly that one
constructor by reflection; `Scheduler`, `FunctionQueueState`, `QueueManager.getOrCreate`,
`QueueManager.enqueue` and `QueueManager.remove` are all ordinary public API, as the in-tree
precedent already uses them. **Nothing in the engine's new API is reached by reflection or at all**
— the new arm is constructed exactly as `SchedulerSwitchBenchmark.Run` constructs it.

The old loop logs through logback, whose default configuration writes to **stdout** — the stream the
artifact is on. `run-old.sh` writes a `logback.xml` routing the root logger to stderr, so the
artifact stays parseable and the loop's own error path still reaches `raw/*.err`.

**One piece of the harness is unused, and is declared rather than removed.** The CLI carries
`--out=<path>` (mirroring the committed harness's own), which this campaign never passes: the
artifact's destination is `run-old.sh`'s tee. It is retained because *any* edit to
`OldLoopComparison.java` changes the digest that both artifacts' header lines record, and re-running
a verified 60-run campaign to delete four unused lines is the wrong trade this late — the digest
tracing the artifact to a commit is worth more than the tidiness.

## 5. The semantic mismatches: designed around, declared, and one the brief missed

The reconnaissance behind the brief mapped the disparities that decide whether this comparison means
anything. What was done with each:

### 5.1 M2 — admission differs in *kind*, not degree: aligned, and the residual is in the artifact

The engine bounds the **total** offered-but-not-submitted population
(`PendingWorkStore(profile.maxPending)`); the old async loop bounds **each function's** own queue
plus its dispatch reservations (`FunctionQueueState.queueSize`). The alignment applied is

```
old queueSize per function = max(1, floor(profile.maxPending / profile.functions.length))
```

so the two arms' admission bounds have the **same total**. They still cannot refuse the same offer
at the same instant: the engine refuses globally, the old loop refuses per function. That residual
is not hidden — every sample carries `offered`, `admitted` and `admissionRejected`, and §7's
coverage table prints them side by side. It is zero everywhere except on `saturated`, where it is
large and is explained there.

### 5.2 M3 — expiry exists only on the new side: declared, and the direction chosen is *no reaper*

The old async module has no deadline at all: `git grep -E "expir|deadline|Clock|maxQueueWait"` over
`platform/modules/async-queue/src/main` at the pre-refactor revision `05f49dcb` matches **zero
files** (the same grep over `platform/modules/sync-queue/src/main` matches five — the *sync* side has
a queue wait, which is M8, and is one of the reasons §6 excludes it). Both directions the brief
names are unfair. **This comparison leaves the old loop without a reaper and declares it.**

The reason is not only fairness but expressibility: a reaper that shadows the engine's would have to
remove *stale* entries from `FunctionQueueState`, and the only mutators that loop's API exposes are
`poll()` and `pollForDispatch()` — head-only, FIFO — plus `closeAndDrainQueued()`, which closes the
state. Reaping a non-head entry would mean draining and re-offering the survivors, which reorders
the queue and can fail on a full one. A reaper written that way would be a new policy of the
driver's, not a behaviour of the old loop, and the old loop's own p99 would be measuring it.

The consequence is declared in the artifact (`expiryInOldArm` in the header) and per profile in §7's
coverage table: where the engine's `expired` is non-zero, the two arms differ by the **expiry
policy** as well as by the loop, and those rows are flagged `*`. The old arm's `expired` is 0 in all
60 runs by construction.

### 5.3 M5 — the claim cadence *is* the object, not a defect

Reported as the result. §7's comparison is where it shows.

### 5.4 M6 — the completion wiring charges the driver differently: declared

The two arms do not charge the driver the same work on a completion, and the difference is real but
its **net sign is not measured by this harness**. In the old arm the release path is production code
inside `QueueManager.tryAcquireLease`: a micrometer `recordDispatchSlotHold`, and a `notifyWork` that
fires only when `expected.queued() > 0`. In the new arm it is the driver's `releasedNanos ->
engine.signal()`, which takes the engine's gate, increments `wakeSequence`, clears the blocked set
and calls `notifyAll()` — on **every** completion, unconditionally. One path does conditional
bookkeeping, the other an unconditional lock-and-notify; which costs more is not something the
artifact reports, and an earlier draft of this section asserted the first was the larger and used
that to argue the CPU figures were charged against the old arm. **That inference is withdrawn**: the
direction was not measured, and it may be the reverse.

What survives is the reason to state M6 at all: the two arms' CPU figures include different
driver-side work, so a CPU delta is not purely the loop's. And there is a candidate for the extra
work in the new arm that is worth naming, since it is this task's own subject: an unconditional wake
on every completion is what a one-claim-per-pass cadence needs (`SchedulerEngine` re-examines
capacity after each ticket), whereas the old loop's up-to-two-per-visit cadence can afford to wake
only when its queue is non-empty. If that is where the CPU difference lives, it is M5, not
bookkeeping.

### 5.5 M9 — neither loop's time is fully controllable: measured in real time

The old async loop's `LongSupplier` affects diagnostics only (its pacing is a real
`activeFunctions.poll(500, MILLISECONDS)` and `loop()` is private), and `SyncScheduler` hardcodes
`Instant.now()`. No fake clock is used anywhere; the engine's `nanoTime` supplier is bound to
`System::nanoTime`, as the committed harness does.

### 5.6 M11 — CPU and heap are process-wide: designed around, not merely declared

Satisfied by construction rather than by assertion: one JVM, both arms, identical thread count,
identical heap flags, identical allocation profile, alternating order. This is the strongest form of
M11 available and it is why the heap comparison in §7 is quotable at all.

### 5.7 Not found in the brief's list: **readiness**, and it costs a profile

The brief's included set names **`head-of-line-blocking`**, and that workload is *defined* by
`notReadyCount = 5`. The gate it exercises is `EngineReadiness`, and **`EngineReadiness` does not
exist at the pre-refactor revision**: `git grep -l EngineReadiness -- '*.java'` at `05f49dcb` matches
**0 files** and at HEAD **15**; `notReady` matches 0 at OLD. (The greps have to be run with a
concrete pathspec — `'platform/*/src/main'` style patterns match nothing at all under `git grep`, so
a zero from them proves nothing; an earlier draft of this section quoted one and its evidence was
void.) What the old tree *does* have is a different concept with a similar name: *deployment*
readiness, `DeploymentReadiness.ensureReady(task)`, which the alternation `readiness|Readiness`
matches in **25** `.java` files (16 for either term alone, 34 for the union with `isReady`) and which
is consulted from the **execution** path (`ExecutionCompletionHandler`), not from the
loop's selection — and which is a **wait-for-wake**, a `CompletableFuture` that completes once the
backend can take the attempt, not a predicate that withholds a ticket.

So driving the profile on the old arm would mean one of two inventions. Dispatching the 5 not-ready
functions' work is the first: a difference of policy the refactor introduced (not of loop), in the
old arm's favour, which would fabricate a result. The second is a gate in the old loop that does not
exist there — and the old loop's only capacity lever is not a substitute, because at OLD capacity is
a *configured* property and `FunctionCapacityState` clamps it to `Math.max(1, concurrency)`, so a
zero-capacity "not dispatchable" mapping is not merely unfaithful but impossible.

The profile's subject is a third reason, independent of the gate: head-of-line blocking is a property
of a **shared scan** that visits many functions and can be held up by one blocked head, and the old
loop has no such scan at all — `Scheduler.processFunction` takes one function per poll and ends the
visit when its lease does not come. There is no structure in the old arm for this workload to
exercise.

**So `head-of-line-blocking` is excluded from this comparison, and the reason is a mismatch the
brief's list does not contain.** The brief's numbering also skips M4 and M10, which is consistent
with the reconnaissance not having reached this one. The harness *refuses* a profile with
`notReadyCount > 0` rather than silently skipping its readiness (`checkSupported`), so an attempt to
run it fails loudly.

### 5.8 The other refusals, made loud rather than silent

`checkSupported` also refuses a profile with a burst, a capacity change or a prefill: those driver
features are not implemented for this comparison, and a profile whose `note` describes a workload
that did not run is worse than a skipped profile. That is why `heterogeneous-burst`,
`capacity-change` and `switch-under-load` are not in the covered set.

## 6. Coverage, and what is not covered

**Covered**, `--profiles=low-load,saturated,unqueued,queued,churn-drain,mixed-kind-retry`,
`--repetitions=5`: six profiles × two arms × five repetitions = **60 measured runs**, and all 60
conserve work (admitted == completed + expired + removed + rejected + pending + claimed +
submitting + in flight). Zero driver failures, zero sample-cap overruns.

**Not covered, with the reason:**

| excluded | reason |
|---|---|
| `head-of-line-blocking` | readiness is new-at-HEAD only; no OLD counterpart (M4, §5.7) |
| `hot-plus-500-sporadic` | M2: 501 × queueSize vs one global bound cannot be aligned |
| `heterogeneous-burst` | burst not implemented; refused loudly by `checkSupported` |
| `capacity-change` | capacity change not implemented; refused loudly |
| `switch-under-load` | burst + readiness, both of the above |
| **the sync arm** (old `SyncScheduler`/`SyncQueueService` vs new `SharedQueueSchedulingStrategy`) | **not attempted in this pass.** M8 says it is comparable only when `syncQueueMaxQueueWait == contractMs` per profile — a configurable `SyncQueueConfigSource` makes that alignment possible, and the committed `SyncQueueInvocationEnqueuer` + `SyncQueueService` wiring is in `SyncSchedulerWakeupTest`. It is a real second comparison and it is not in this document; nothing here speaks for the shared-queue side |
| `unqueued`'s "direct admission path" | unchanged from Task 12c: it lives in the control-plane, so both arms run the engine-level approximation, as the profile's own note says |

**Residual coverage facts, stated rather than implied:**

- `churn-drain` stops offering traffic at 60 % of the span (`stopAtPercent`), so its trailing
  2000 ms steady window holds **no arrivals** and its two `window` metrics are NOT MEASURABLE. Its
  four whole-run metrics are reported; per Task 12c's own reasoning a workload that stops its
  traffic is not exempt from them.
- `saturated`'s control arm has a steady-window useful throughput of **0**, so that one metric is
  NOT MEASURABLE (a ratio with a zero denominator is not a figure).
- **`offered` differs between the arms in three of the thirty (workload, repetition) pairs, always
  by one ticket, and two different mechanisms account for them.** One is the window-close race — the
  last scripted arrival of the span falls either side of the loop's own observational instant — and
  the other is the **reactive retry draw**, which is unscripted. The analysis names which is which
  per pair, because the retry mechanism alone does not explain all three: `mixed-kind-retry`
  repetition 1 differs with its retry count differing by the same one (the reactive draw);
  `mixed-kind-retry` repetition 3 and `unqueued` repetition 5 differ with retry counts **equal** (the
  close race). The medians agree (9510/9510, 2833/2834) and no conclusion rests on any of this — but
  the equal medians are all the medians show, and an explanation that stopped at the retry draw
  would be wrong about two pairs.
- `allocatedBytesPerUsefulCompletion` and the engine's reaper interaction: see §7.

## 7. The comparison

### 7.1 The artifact, and how every figure below was produced

```bash
docs/experiments/scheduler-switching-2026-09/run-old.sh --label=old-vs-new --repetitions=5
docs/experiments/scheduler-switching-2026-09/old-vs-new.py raw/old-vs-new.jsonl
```

- `raw/old-vs-new.jsonl` — 61 JSONL lines: one `header`, 60 `sample`. Revision
  `c64da071` (the commit that added the arm), harness
  `OldLoopComparison.java` sha256 `761908913d93…`, committed harness
  `SchedulerSwitchBenchmark.java` sha256 `353ee27881ef…`. All three travel in the header line.
- `raw/old-vs-new.err` — the run's stderr (logback).
- `raw/old-vs-new-analysis.txt` — `old-vs-new.py`'s output, **verbatim**, which is what the tables
  in this section are.
- `raw/smoke-old.jsonl`, `raw/smoke-old.err` — the smoke run (§7.5). Its header records revision
  `7beabc33` and the same harness digest as the campaign's, because it ran before the harness was
  committed; the digest identifies the build, the revision records where the tree was. The
  campaign artifact's pair is `c64da071` + `761908913d93`, which exists in history.
- `raw/load-average-samples-old-vs-new.txt` — the host-quietness record (§7.6).

### 7.2 Coverage and the expiry confound

| workload | old reps | new reps | new expired | old expired | offered old/new | admitted old/new | policy-confounded |
|---|---|---|---|---|---|---|---|
| churn-drain | 5 | 5 | 0 | 0 | 6180/6180 | 6180/6180 | no |
| low-load | 5 | 5 | 0 | 0 | 194/194 | 194/194 | no |
| mixed-kind-retry | 5 | 5 | 0 | 0 | 9510/9510 | 9510/9510 | no |
| queued | 5 | 5 | 41 | 0 | 2830/2830 | 2830/2830 | yes |
| saturated | 5 | 5 | 14524 | 0 | 18926/18926 | 4780/18926 | yes |
| unqueued | 5 | 5 | 0 | 0 | 2833/2834 | 2833/2834 | no |

For `queued`, 41 of 2830 expiries is 1.4 % — the confound is present but small. For `saturated`,
14524 of 18926 is **77 %**, and that profile's comparison is a comparison of two *policies*, not two
loops: see §7.4.

### 7.3 The settlement check, on the committed rule

| workload | arm | settled depth | settle ms (worst rep) | settled before the steady window | arrivals in the steady window |
|---|---|---|---|---|---|
| churn-drain | old-async (no change) | 0-0 | 5000 | yes | 0 |
| churn-drain | per-function (no change) | 0-0 | 5000 | yes | 0 |
| low-load | old-async (no change) | 0-0 | 0 | yes | 43 |
| low-load | per-function (no change) | 0-0 | 0 | yes | 42 |
| mixed-kind-retry | old-async (no change) | 3-4 | 50 | yes | 1966 |
| mixed-kind-retry | per-function (no change) | 2-4 | 0 | yes | 1966 |
| queued | old-async (no change) | 6-29 | 2400 | yes | 599 |
| queued | per-function (no change) | 4-9 | 0 | yes | 589 |
| saturated | old-async (no change) | 513-513 | 0 | yes | 348 |
| saturated | per-function (no change) | 109-112 | 50 | yes | 894 |
| unqueued | old-async (no change) | 0-1 | 0 | yes | 604 |
| unqueued | per-function (no change) | 0-1 | 0 | yes | 603 |

The old loop reaches a steady state in every workload where a steady state exists — including
`queued`, where it takes up to 2400 ms of settled time against the new engine's 0 ms, and where its
settled depth ranges 6–29 across the five repetitions against the new engine's 4–9. That spread is
the reason `queued`'s steady figures below resolve poorly.

### 7.4 The per-profile comparison

`paired` is the median of the five within-repetition differences `(new − old)/old`. `separated` says
whether all five agree in sign — the only thing that makes the median a measured effect rather than
one draw. Verdicts apply the frozen budgets with a regression read as the new engine being *worse*:
upward for p99, CPU per useful completion, heap and allocation; downward for useful throughput.

Two rows of `old-vs-new.py`'s table are **not** reproduced here, to keep the table navigable: the
`thread cpu per window` row for each profile, which §9.3 quotes and which
`raw/old-vs-new-analysis.txt` carries in full, and the settlement and resolving-power tables, which
are §7.3 and §8 verbatim.

| workload | metric | session | old | new | unpaired Δ % | paired Δ % | paired range | separated | budget % | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| churn-drain | whole-span p99 | whole | 2.729 | 2.641 | -3.24 | -4.10 | -7.06…+5.14 | no | 5 | PASS |
| churn-drain | steady p99 | window | — | — | — | — | — | — | 5 | NOT MEASURABLE (fewer than 30 arrivals in the steady window) |
| churn-drain | whole-span useful throughput | whole | 772.497 | 772.327 | -0.02 | -0.01 | -0.02…+0.02 | no | 5 | PASS |
| churn-drain | steady useful throughput | window | — | — | — | — | — | — | 5 | NOT MEASURABLE (fewer than 30 arrivals in the steady window) |
| churn-drain | thread cpu per useful completion | whole | 42134 | 46977 | +11.49 | +14.75 | -9.94…+72.54 | no | 10 | MISS |
| churn-drain | post-GC heap | whole | 77449232 | 77255304 | -0.25 | -0.25 | -0.25…-0.25 | yes | 10 | PASS |
| churn-drain | allocated bytes per useful completion | whole | 3885 | 4033 | +3.81 | +5.50 | +1.59…+5.93 | yes | — | no budget |
| low-load | whole-span p99 | whole | 2.778 | 2.814 | +1.29 | +1.29 | -9.48…+5.63 | no | 5 | PASS |
| low-load | steady p99 | window | 2.570 | 2.793 | +8.67 | +5.40 | -19.52…+9.36 | no | 5 | MISS |
| low-load | whole-span useful throughput | whole | 24.243 | 24.247 | +0.02 | +0.00 | -0.01…+0.02 | no | 5 | PASS |
| low-load | steady useful throughput | window | 21.500 | 21.000 | -2.33 | +0.00 | -2.33…+0.00 | no | 5 | PASS |
| low-load | thread cpu per useful completion | whole | 755178 | 768948 | +1.82 | +1.62 | +0.95…+11.25 | yes | 10 | PASS |
| low-load | post-GC heap | whole | 77271168 | 77244288 | -0.03 | -0.03 | -0.04…+0.25 | no | 10 | PASS |
| low-load | allocated bytes per useful completion | whole | 1654 | 1959 | +18.44 | +27.75 | +8.77…+45.92 | yes | — | no budget |
| mixed-kind-retry | whole-span p99 | whole | 5.153 | 5.258 | +2.05 | +1.79 | +0.87…+6.98 | yes | 5 | PASS |
| mixed-kind-retry | steady p99 | window | 5.283 | 5.186 | -1.83 | +3.41 | -3.49…+8.47 | no | 5 | PASS |
| mixed-kind-retry | whole-span useful throughput | whole | 1188.449 | 1188.449 | +0.00 | +0.00 | -0.02…+0.03 | no | 5 | PASS |
| mixed-kind-retry | steady useful throughput | window | 983.000 | 983.000 | +0.00 | +0.00 | -0.05…+0.15 | no | 5 | PASS |
| mixed-kind-retry | thread cpu per useful completion | whole | 36995 | 47492 | +28.37 | +29.02 | +17.96…+47.92 | yes | 10 | MISS |
| mixed-kind-retry | post-GC heap | whole | 77272840 | 77245336 | -0.04 | -0.04 | -0.04…-0.03 | yes | 10 | PASS |
| mixed-kind-retry | allocated bytes per useful completion | whole | 1316 | 1517 | +15.27 | +14.69 | +11.79…+16.44 | yes | — | no budget |
| queued * | whole-span p99 | whole | 124.134 | 87.116 | -29.82 | -25.53 | -40.65…+33.49 | no | 5 | PASS |
| queued * | steady p99 | window | 99.280 | 87.532 | -11.83 | -6.57 | -43.48…+78.98 | no | 5 | PASS |
| queued * | whole-span useful throughput | whole | 331.786 | 348.066 | +4.91 | +8.27 | -1.30…+18.26 | no | 5 | PASS |
| queued * | steady useful throughput | window | 293.500 | 294.500 | +0.34 | -0.16 | -3.57…+74.26 | no | 5 | PASS |
| queued * | thread cpu per useful completion | whole | 90045 | 110649 | +22.88 | +27.84 | -0.40…+55.95 | no | 10 | MISS |
| queued * | post-GC heap | whole | 77273032 | 77247592 | -0.03 | -0.03 | -0.04…-0.03 | yes | 10 | PASS |
| queued * | allocated bytes per useful completion | whole | 1259 | 1450 | +15.17 | +18.51 | +9.69…+35.51 | yes | — | no budget |
| saturated * | whole-span p99 | whole | 1201.337 | 62.004 | -94.84 | -94.84 | -95.05…-94.74 | yes | 5 | PASS |
| saturated * | steady p99 | window | 1206.798 | 61.935 | -94.87 | -94.87 | -95.09…-94.49 | yes | 5 | PASS |
| saturated * | whole-span useful throughput | whole | 4.250 | 456.857 | +10649.95 | +10594.13 | +9271.79…+12746.66 | yes | 5 | PASS |
| saturated * | steady useful throughput | window | 0 | — | — | — | — | — | 5 | NOT MEASURABLE (the control arm's own value there is zero) |
| saturated * | thread cpu per useful completion | whole | 7354219 | 169136 | -97.70 | -97.92 | -98.31…-97.68 | yes | 10 | PASS |
| saturated * | post-GC heap | whole | 77466240 | 77310912 | -0.20 | -0.19 | -0.20…-0.16 | yes | 10 | PASS |
| saturated * | allocated bytes per useful completion | whole | 449984 | 5867 | -98.70 | -98.71 | -98.76…-98.46 | yes | — | no budget |
| unqueued | whole-span p99 | whole | 4.295 | 4.295 | +0.00 | +2.04 | -11.41…+24.22 | no | 5 | PASS |
| unqueued | steady p99 | window | 4.258 | 4.154 | -2.45 | +1.73 | -6.59…+3.06 | no | 5 | PASS |
| unqueued | whole-span useful throughput | whole | 354.112 | 354.046 | -0.02 | +0.01 | -0.02…+0.01 | no | 5 | PASS |
| unqueued | steady useful throughput | window | 302.000 | 301.500 | -0.17 | +0.00 | -0.17…+0.00 | no | 5 | PASS |
| unqueued | thread cpu per useful completion | whole | 86299 | 95828 | +11.04 | +18.67 | -3.95…+77.75 | no | 10 | MISS |
| unqueued | post-GC heap | whole | 77271552 | 77244752 | -0.03 | -0.03 | -0.03…-0.03 | yes | 10 | PASS |
| unqueued | allocated bytes per useful completion | whole | 1100 | 1399 | +27.18 | +27.51 | +23.64…+30.46 | yes | — | no budget |

Medians per arm, produced by the committed `summarize.workload_table` on this artifact:

| workload | arm | reps | useful/s | p99 ms | thread cpu/useful us | alloc/useful B | post-GC heap MB | pending | conserved |
|---|---|---|---|---|---|---|---|---|---|
| churn-drain | old-async (no change) | 5 | 772.5 (757.6-780.1) | 2.729 | 42.13 | 3885 | 77.45 | 0 | True |
| churn-drain | per-function (no change) | 5 | 772.3 (757.6-780.2) | 2.641 | 46.98 | 4033 | 77.26 | 0 | True |
| low-load | old-async (no change) | 5 | 24.2 (21.7-26.5) | 2.778 | 755.18 | 1654 | 77.27 | 0 | True |
| low-load | per-function (no change) | 5 | 24.2 (21.7-26.5) | 2.814 | 768.95 | 1959 | 77.24 | 0 | True |
| mixed-kind-retry | old-async (no change) | 5 | 1188.4 (1175.6-1193.1) | 5.153 | 36.99 | 1316 | 77.27 | 0 | True |
| mixed-kind-retry | per-function (no change) | 5 | 1188.4 (1175.6-1193.2) | 5.258 | 47.49 | 1517 | 77.25 | 0 | True |
| queued | old-async (no change) | 5 | 331.8 (293.5-350.9) | 124.134 | 90.05 | 1259 | 77.27 | 4 | True |
| queued | per-function (no change) | 5 | 348.1 (346.3-359.2) | 87.116 | 110.65 | 1450 | 77.25 | 3 | True |
| saturated | old-async (no change) | 5 | 4.2 (3.7-4.9) | 1201.337 | 7354.22 | 449984 | 77.47 | 512 | True |
| saturated | per-function (no change) | 5 | 456.9 (408.7-481.7) | 62.004 | 169.14 | 5867 | 77.31 | 103 | True |
| unqueued | old-async (no change) | 5 | 354.1 (344.1-358.5) | 4.295 | 86.30 | 1100 | 77.27 | 0 | True |
| unqueued | per-function (no change) | 5 | 354.0 (344.1-358.5) | 4.295 | 95.83 | 1399 | 77.24 | 0 | True |

### 7.5 The smoke run, verbatim

`./run-old.sh --label=smoke-old --profiles=low-load --repetitions=1` produces `raw/smoke-old.jsonl`:
a `header` line and two `sample` lines. The two samples' figures, as emitted:

| field | old-async (no change) | per-function (no change) |
|---|---|---|
| offered rate/s | 20.0 | 20.0 |
| offered / admitted / admissionRejected | 208 / 208 / 0 | 208 / 208 / 0 |
| completed / useful | 208 / 208 | 208 / 208 |
| expired / removed / rejected | 0 / 0 / 0 | 0 / 0 / 0 |
| accountingClosure / workConserved | 208 / true | 208 / true |
| p50Nanos / p99Nanos | 2708792 / 3046071 | 2456633 / 3085447 |
| samplesRecorded / windowMillis | 172 / 8001 | 172 / 8000 |
| depthSeries length | 158 | 158 |
| threadCpuPerUsefulCompletionNanos | 963058 | 994681 |
| postGcHeapBytes | 76999840 | 77199840 |
| driverFailures | 0 | 0 |

`offered = 208` on both arms is the arrival script's own figure, and both counts that belong to it
are derivable from the committed Java. The script's horizon is `WARMUP_MS + SPAN_MS + 1_000` =
**10 500 ms** (`OldLoopComparison.java:773`, the extra second absorbing scheduling slack), and it
holds **226** arrivals for `low-load` over that horizon; the driver stops offering at the **window
close**, `WARMUP_MS + SPAN_MS` = **9 500 ms**, below which the same script holds **208** — which is
what both samples record. The two arms are therefore offered the same count by construction rather
than by coincidence. (An earlier revision of this paragraph said the *script* ended at 9 500 ms;
that is the driver's window close, not the horizon.) Across the whole campaign the
analysis prints the check rather than asserting it: **`offered` is identical in 27 of the 30
(workload, repetition) pairs, and the three that differ differ by exactly one ticket**, with the
cause named per pair (§6).

### 7.6 Was the host quiet?

`raw/load-average-samples-old-vs-new.txt`: **122 samples of `/proc/loadavg` spanning the campaign's
602 s** (17:22:24Z to 17:32:26Z), strictly increasing, 5 s apart except one 2 s step.
**1-minute load average: min 0.10, median 0.29, max 0.61** on 20 processors — 0.5 % to 3 % of the
host — and nothing else CPU-bound was started during it. **The harness's own CPU figure agrees:**
every sample carries `threadCpuNanos`, the summed CPU of every live thread over its 8 000 ms window,
and across the 60 runs that is **140.6 ms to 658.0 ms, median 276.3 ms** — 1.8 % to 8.2 % of one
core, median 3.5 %, shared by both arms, the driver and the JVM's own housekeeping. The Gradle daemon
was stopped by `run-old.sh` before the measured JVM started, and the queue modules' jars were rebuilt
(and found up to date) *before* that.

Two provenance notes about that file, because it is not a recording of one clean run. **Two samplers
were started for this campaign** — the first in a command that failed before the campaign launched —
so the file merges two 5 s series from the same host; the one 2 s step is where the survivor's line
lands on top of the first one's, and the two agree to within a sample. It was also **truncated to
the campaign window** (78 post-campaign lines dropped) and reduced to a strictly increasing series
(1 out-of-order duplicate dropped). The samples themselves are unmodified; only post-campaign lines
and the duplicate were removed, and the level figures above are the remaining 122.

## 8. Resolving power, and what the pairing bought

Both operations below read the arms, never the observed effect, and are deterministic. **Unpaired**
multiplies the old arm's own five values by `(1 + f)` — a perfectly uniform change with no added
noise, the most favourable case for the test — and counts how often the shifted arm reads disjoint
from the new arm's range. That is the operation a cross-run design can perform, and it is what
Task 12c had. **Paired** asks how large `f` must be before all five within-repetition differences
take the sign `f` implies.

| metric | budget % | eligible | unpaired +5 % | +10 % | +20 % | +30 % | +50 % | +100 % | median unpaired arm spread % | median paired spread % | median smallest paired same-signed effect % |
|---|---|---|---|---|---|---|---|---|---|---|---|
| whole-span p99 | 5 | 6 | 1 | 2 | 4 | 5 | 5 | 6 | 17.49 | 13.66 | 5.38 |
| steady p99 | 5 | 5 | 1 | 1 | 2 | 2 | 4 | 5 | 22.10 | 11.96 | 3.49 |
| whole-span useful throughput | 5 | 6 | 3 | 3 | 3 | 5 | 5 | 5 | 10.87 | 0.05 | 0.01 |
| steady useful throughput | 5 | 4 | 1 | 1 | 2 | 2 | 2 | 4 | 24.12 | 1.26 | 0.03 |
| thread cpu per useful completion | 10 | 6 | 1 | 1 | 1 | 1 | 2 | 5 | 33.31 | 43.15 | 0.20 |
| post-GC heap | 10 | 6 | 6 | 6 | 6 | 6 | 6 | 6 | 0.01 | 0.01 | 0.00 |
| allocated bytes per useful completion | — | 6 | 1 | 1 | 2 | 3 | 6 | 6 | 9.57 | 5.73 | 0.00 |
| thread cpu per window | — | 6 | 0 | 0 | 0 | 0 | 1 | 4 | 28.44 | 37.60 | 0.00 |

**Reading it.** The unpaired columns are the reason Task 12c could not adjudicate a 5 % or 10 %
budget: on p99 the arms' own medians spread 17.5 % (whole-span) and 22.1 % (steady) between
repetitions, and a *perfectly uniform* 5 % shift of the control arm would separate the arms in
**1 workload of 6** (whole-span) and **1 of 5** (steady); even at 20 % it separates **4 of 6** and
**2 of 5**. CPU per useful completion is the same: 1 of 6 at 5 %. Only post-GC heap was ever
adjudicable unpaired, and only because its arms agree to 0.01 %.
**The paired design resolves the same metrics to a median of 3.5 % (steady p99), 5.4 %
(whole-span p99) and 0.01–0.03 % (useful throughput)** — because the arrival script is identical and
the completion counts are then nearly identical, so the throughput figure is close to deterministic.
That is what the pairing buys, and it is what makes §7.4's nulls readable.

**Those medians are aggregates across workloads and are not any one workload's resolution.** A
profile whose five paired differences already agree in sign contributes `0.00` *by construction*:
`0.00` there means the arms were already separated, which is not a fine resolution at all, and
averaging it in pulls the median down. **How many profiles do that is metric-dependent, and the
spread is wide, so it is stated per metric** — counted from the table beneath: whole-span p99 **2**,
steady p99 **1**, whole-span throughput **1**, steady throughput **2**, thread CPU per useful
completion **3**, post-GC heap **5**, allocated bytes per useful completion **6**. `saturated` is one
of them everywhere except steady throughput, where it is `n/a` rather than `0.00`. On the two
metrics where five or six of the six profiles sit at `0.00` — post-GC heap (five) and allocated
bytes (all six) — that column's median is close to meaningless, which is the whole reason the table, and
not the median, is what a reader should quote. The table beneath
gives the number to use whenever a specific workload's effect is established or dismissed, and it is
the one quoted in §9.1 and §9.5:

| workload | whole-span p99 | steady p99 | whole-span throughput | steady throughput | cpu per useful | alloc per useful | post-GC heap |
|---|---|---|---|---|---|---|---|
| `churn-drain` | 5.14 | n/a | 0.02 | n/a | 9.94 | 0.00 | 0.00 |
| `low-load` | 5.63 | **9.36** | 0.01 | 0.00 | 0.00 | 0.00 | 0.04 |
| `mixed-kind-retry` | 0.00 | 3.49 | 0.02 | 0.05 | 0.00 | 0.00 | 0.00 |
| `queued` | **33.49** | **43.48** | 1.30 | 3.57 | 0.40 | 0.00 | 0.00 |
| `saturated` | 0.00 | 0.00 | 0.00 | n/a | 0.00 | 0.00 | 0.00 |
| `unqueued` | **11.41** | 3.06 | 0.01 | 0.00 | 3.95 | 0.00 | 0.00 |

Whoever quotes the aggregate for a workload with a number like `queued`'s 43.48 or `unqueued`'s 11.41
is claiming a sharpness that workload does not have. The sweep that produced this table is §12.

**One metric the pairing did not sharpen: CPU per useful completion.** Its paired spread (43.15 %)
is *larger* than its unpaired arm spread (33.31 %), so the noise in it is not host drift — it is
intra-run JVM activity (GC and JIT thread CPU inside the window), which the arms share but not
identically. Its budget of 10 % is therefore **below the resolution of this design on that metric**,
and the four `MISS` rows in §7.4 are reported as misses of a figure this design cannot resolve,
except where all five repetitions agree in sign — see §9.2.

## 9. What the comparison says

### 9.1 The nulls, with their resolution

- **Post-GC heap: indistinguishable, at a resolution of 0.01 %.** Every one of the six profiles'
  paired deltas is 5-of-5 same-signed and lies between −0.25 % and −0.03 %, and that resolution is
  the median paired spread of §8's paired-spread column (the arms' medians are identical to two
  decimals: 77.24–77.45 MB). The new engine's retained heap is not measurably different from the
  old loop's, against a 10 % budget.
- **Useful throughput: indistinguishable on the four profiles this design resolves it on, to
  0.00–0.05 %.** `low-load`, `unqueued`, `churn-drain` and `mixed-kind-retry`: their whole-span paired deltas run
  **−0.02 % to +0.03 %**, and their own resolutions on the **whole-span throughput column** of §8's
  table are **0.01, 0.01, 0.02 and 0.02 %**. (Their steady-window resolutions are a different column
  — 0.00, 0.00, n/a and 0.05 % — and an earlier revision quoted the union of the two as one list.)
  This is the finest instrument in this document, because the arrival script is identical. The engine does
  not lose or gain useful work relative to the old loop there.
- **`queued`'s throughput is the one exception worth stating**: its median is **+8.27 %** in the new
  engine's favour and its own resolution is 1.30 %, but one of the five repetitions is −1.30 %, so the
  direction is suggested and **not** established. It is reported as a suggestion, not as a result.
- **p99: no difference established anywhere except `mixed-kind-retry`'s whole-span figure.** That one
  is same-signed at **+1.79 %** (range +0.87…+6.98) and below the 5 % budget, so a PASS with a
  measured direction. Elsewhere the five repetitions straddle zero, and the workloads' own
  resolutions on p99 are **0.00–43.48 % over the five profiles whose steady window is measurable**
  — that `0.00` is `saturated`'s, and excluding it the steady range is 3.06–43.48 % — and
  **0.00–33.49 % over all six on the whole-span figure** — those zeros are the two profiles already
  separated on that metric (`saturated` and `mixed-kind-retry`), so excluding those the whole-span
  range is 5.14–33.49 %. On `queued` no 5 % effect could have been established at all, and on
  `low-load`'s steady p99 not below 9.36 %.
- **`low-load`'s steady p99 is a budget MISS: +5.40 %.** It is reported as a MISS and not excused.
  Its ground is the straddle: the five paired differences run −19.52 % to +9.36 %, so they fall on
  both sides of zero and the median is not a measured effect. Its *second* ground, which an earlier
  draft got backwards, is this **workload's own** resolution on this metric — **9.36 %**, from the
  table in §8 — not the 3.5 % aggregate, which is a median over five workloads of which **one**
  contributes 0 by construction (`saturated`; the other four are 3.06, 3.49, 9.36 and 43.48). Read against its own number the sentence is now consistent: a
  uniform effect of +5.4 % is smaller than the 9.36 % this design could have seen on `low-load`, and
  the straddle says the same thing independently. In absolute terms the figure is 2.570 ms → 2.793 ms
  against a 100 ms contract, 0.22 ms.

### 9.2 The one direction that is both measured and consistent: allocation

**The new engine allocates more per useful completion in every repetition of every profile that is
not expiry-confounded — and of `queued` too, whose `*` flag is a small confound stated here rather
than waved away.** The two rows below are the same five workloads:

| workload | alloc/useful old B | new B | paired Δ % | paired range | window total Δ % | window total range | separated |
|---|---|---|---|---|---|---|---|
| churn-drain | 3885 | 4033 | +5.50 | +1.59…+5.93 | +5.50 | +1.58…+5.92 | yes |
| mixed-kind-retry | 1316 | 1517 | +14.69 | +11.79…+16.44 | +14.74 | +11.79…+16.42 | yes |
| queued `*` | 1259 | 1450 | +18.51 | +9.69…+35.51 | +31.19 | +28.28…+34.89 | yes |
| unqueued | 1100 | 1399 | +27.51 | +23.64…+30.46 | +27.53 | +23.69…+30.46 | yes |
| low-load | 1654 | 1959 | +27.75 | +8.77…+45.92 | +27.76 | +8.80…+45.84 | yes |

**Both spans, because they answer different questions, and all three sets named, because one word
was doing two jobs.** Three nested sets are in play, and an earlier revision called two of them
"unconfounded" in the same paragraph:

| set | pairs | which | what it is for |
|---|---|---|---|
| expiry-clean | **20** | the four profiles that are neither `queued` nor `saturated` | the direction's independent ground |
| not `saturated` | **25** | those four plus `queued` | the spans quoted here |
| all measured | **30** | those five plus `saturated` | nothing — `saturated` is confounded |

The **per-profile medians** of the allocation delta span **+5.50 % to +27.75 %** — that is the
sentence's headline, and it is a statement about five medians. The **repetition-level** deltas span
**+1.59 % to +45.92 %** for allocation per useful completion, and the **window total** spans
**+1.58 % to +45.84 %** — both over the 25 pairs that are not `saturated`, and both identical over
the 20 expiry-clean ones. (The all-30 window total reaches +65.00 %, on `saturated`.)

The direction holds on every reading and is **not rounded down**: the smallest repetition-level
allocation delta is **+1.59 %**, the smallest window-total one is **+1.58 %**, and both are
same-signed across all five repetitions of every profile in the 25 — including the four that are
expiry-clean, where the direction stands without `queued` at all. The `queued` confound is an expiry share of **1.0 % to 2.5 %** of its offers across the five
repetitions, against a `+18.51 %` allocation delta, and removing `queued` entirely leaves the
direction established on the four genuinely clean profiles.

The profiles it holds across have offered rates from **20/s (`low-load`) to 1 120/s (`churn-drain`)**
— read off the header's own `profilesInRun` block, and offered rates rather than the useful-throughput
figures an earlier draft mistook for them (24.2/s and 1 188.4/s were the *throughput* medians). No
frozen budget covers allocation, so there is no verdict to give — but the direction is consistent and
it is coherent with M5: the old loop amortises one visit's machinery over up to two dispatches, while
the engine's pass selects, claims, commits and finishes one ticket. **The mechanism is not
established by this harness**, and this document does not guess at it beyond that the two
per-dispatch machinery profiles differ. It is the clearest loop-layer signal this comparison
produced.

### 9.3 CPU per useful completion: three misses the design cannot resolve, one it can

The `MISS` rows on `churn-drain` (+14.75 %, range −9.94…+72.54), `queued` (+27.84 %, range
−0.40…+55.95) and `unqueued` (+18.67 %, range −3.95…+77.75) are each a median with the five
repetitions on both sides of zero: the design did not resolve that metric on that profile.
`mixed-kind-retry`'s is different: **+29.02 %, range +17.96…+47.92, 5 of 5 positive, every
repetition above the 10 % budget.** The cross-check in §7.4's script (`thread cpu per window`, the
same measurement with the useful count cancelled out of the denominator) is same-signed positive
there too, at +29.02 % (range +17.96…+47.97), and for `queued` (+30.56 %) and `low-load` (+1.62 %).

So: **on `mixed-kind-retry` the new engine costs measurably more thread CPU for the same useful
work, at a magnitude above the budget in every repetition.** The direction is consistent with the
allocation finding of §9.2, and the mechanism is likewise not established. On `queued` and
`churn-drain` the same direction is suggested by the median and corroborated by the window-CPU
cross-check for `queued`, but not separated on the per-completion figure. On `low-load` the whole
figure is small — 0.76 ms per completion on the old arm against 0.77 ms on the new one — and near
the noise floor.

The one asymmetry in this metric is declared, and **its net sign is not measured**: the two arms'
release paths charge the driver different work (M6, §5.4) — the old arm a conditional micrometer
record and a conditional wake, the new one an unconditional gate-lock-and-notify — and nothing in
this artifact says which costs more. An earlier revision of both documents asserted the charge landed
against the old arm and called its CPU figures conservative on that basis; that inference is
withdrawn. The `mixed-kind-retry` direction above stands on its own repetitions, not on it.

### 9.4 `saturated`: a large, established, and *policy-confounded* difference

| | old | new |
|---|---|---|
| admitted of offered | 4780 of 18926 | 18926 of 18926 |
| admissionRejected | 14149 | 0 |
| expired | 0 | 14524 |
| completed / useful | 4267 / 34 | 4264 / 3655 |
| useful throughput | 4.25/s | 456.86/s |
| whole-span p99 | 1201.3 ms | 62.0 ms |
| thread cpu per useful completion | 7.35 ms | 0.17 ms |
| allocated bytes per useful completion | 449984 | 5867 |

Every paired delta is same-signed, and the direction is the new engine's by a factor of 108 (34
useful completions against 3 655). **It is
also not a loop result.** With `maxPending` 512 and a 2000/s offered rate against a service rate of
500/s, the old loop's queue fills and stays full *of work that is already past its 60 ms contract*,
because it has no deadline and therefore never drops anything: 512 tickets are permanently held,
fresh arrivals are refused at admission (14149 of them), and only 34 completions in the whole run
were inside the contract. The engine reaps: 14524 tickets are dropped before dispatch, which frees
the store, and 3655 useful completions result.

That is a real and favourable difference the refactor introduced — but it is the **expiry policy**
(M3), which this comparison deliberately did not equalise, not the claim cadence. The row is flagged
`*` for exactly this reason, and no part of `saturated`'s delta should be attributed to the loop.

### 9.5 `queued`: a direction suggested, not established

The old loop's whole-span p99 is 124.1 ms against the new engine's 87.1 ms (paired −25.53 %), and
its useful throughput 331.8/s against 348.1/s (paired +8.27 %) — but both ranges cross zero
(−40.65…+33.49 % and −1.30…+18.26 %), and `queued` is also the workload this design resolves worst:
its own resolutions are **33.49 %** on whole-span p99 and **43.48 %** on steady p99 against a 5 %
budget, so no 5 % effect could have been established here whatever the arms did. The settlement table
shows why the design is that blunt on this profile: the old loop's settled depth ranges 6–29 across
repetitions against the engine's 4–9, so this workload's steady state is not stable on the old side. The single-repetition diagnostic run had shown −68.5 % on p99; across
five repetitions the effect is −25.5 % with one repetition at +33 %. **That gap is the whole reason
for five repetitions and for a paired design**, and it is why this document does not report a
`queued` regression or improvement as established.

## 10. What this does not establish

1. **Nothing about the switch.** The old loops have no `switchTo`; the cost and the pause of a
   manual strategy change remain Task 12c's, and its result stands unchanged.
2. **Not level 1** in the spec's taxonomy, as the preamble above says. This is a profile
   comparison; the level-1 number already exists in Task 12c's harness.
3. **No 500-function and no mixed-subsystem comparison** (M1/M2). No profile with 500 sporadic
   functions was run, and the old async and old sync subsystems are not compared against each other
   — at OLD they could not both be enabled.
4. **No absolute better-or-worse verdict.** CPU and post-GC heap are process-wide (M11). This
   design makes them *comparable* by running both arms in one JVM, which is not the same as
   attributing them to a scheduler. Where a CPU direction is reported (§9.3) it is the thread-CPU
   sum over the window, which includes the driver and JVM housekeeping.
5. **Nothing about the async front under production configuration.** In production the async front
   passes `queueDeadline = null` (`EngineInvocationEnqueuer.java:137`) and only the sync front sets
   one (`EngineSyncQueueGateway.java:206-207`); this harness, like Task 12c's, stamps the contract
   on every ticket. **Both arms therefore run a stricter async deadline policy than the system
   ships**, and the expiry figures above are harness figures, not production ones.
6. **No validation of production wiring.** Spring composition, module defaults and profile selection
   are Task 13's HTTP/native tests.

And one more, added by this task rather than inherited:

7. **Nothing about the shared-queue side.** The old sync loop (`SyncScheduler` over
   `SyncQueueService`) is not in this comparison; §6 records why and what it would need.

## 11. Reproducing it

```bash
# the smoke run this task was committed with
docs/experiments/scheduler-switching-2026-09/run-old.sh \
    --label=smoke-old --profiles=low-load --repetitions=1

# the campaign
docs/experiments/scheduler-switching-2026-09/run-old.sh --label=old-vs-new --repetitions=5

# the tables, verbatim into raw/old-vs-new-analysis.txt
docs/experiments/scheduler-switching-2026-09/old-vs-new.py raw/old-vs-new.jsonl

# the committed harness's own view of the same artifact (reuse, not a reimplementation)
python3 -c "import summarize, sys; sys.path.insert(0, '.'); \
  summarize.workload_table(summarize.load('raw/old-vs-new.jsonl')[0])"
```

`run-old.sh` rebuilds the queue modules' jars first (a stale jar would drive a different revision of
the old loop than the one reported), resolves `:control-plane-modules:async-queue`'s test classpath
into `.classpath-async-queue` if the cache is missing or invalid, compiles `OldLoopComparison.java`
together with `SchedulerSwitchBenchmark.java`, stops the Gradle daemon, and runs on a fixed
pre-touched 1 GiB heap. The classpath is that module's test classpath, not `:execution-runtime`'s,
because `Scheduler` implements `SmartLifecycle` and its type does not resolve without Spring; the
async-queue test classpath carries both queue modules, the engine and Spring.

## 12. The sweep this revision ran

The fix round that produced this revision was about one class, not four sites: **prose outrunning the
artifact**. So after fixing the four findings it swept for the class itself, in both this document and
the working report. Five sweeps, each mechanical, each re-runnable:

1. **Aggregated figures used as a workload's figure.** Every occurrence of a resolution, a spread or a
   span was checked for whether the sentence around it names one workload or all of them. It found
   the one the fix round named (the MISS's resolution, now `low-load`'s own 9.36 %) and two more of
   exactly that shape: the p99 and throughput bullets of §9.1 both quoted the cross-workload median,
   and `queued` was silently riding on an aggregate it is the worst case of. All three now quote the
   workload's own number, and §8 carries the whole per-workload table so no reader has to take one on
   trust. The tool prints it (`old-vs-new.py`), it is not hand-built.
2. **Scope words that include a flagged row.** Every "every", "all", "each" and "no" was read against
   the coverage table of §7.2. It found "every unconfounded profile" in §9.2 including `queued`,
   which §7.2 and §7.4 flag `*` — the artifact contradicted the sentence three sections earlier. The
   claim is now scoped to the four expiry-clean profiles plus `queued` with its 1.0–2.5 % confound
   stated, and the finding is shown to survive without `queued` at all.
3. **Numbers whose unit or denominator is not the sentence's.** Every rate, count and ratio was
   checked against the field it came from. It found two: "offered rates span 24/s to 1189/s" — those
   were *useful-throughput* medians, and the offered rates are 20/s to 1 120/s read off the header's
   `profilesInRun` — and the window-total allocation span, which was quoted as "+5.5 % to +38 %" and
   is "+1.58 % to +45.84 %" over the unconfounded pairs (the earlier range was the per-profile
   medians of the *other* metric). Both corrected, and §9.2 now prints both spans side by side
   because they answer different questions rather than being interchangeable.
4. **Claims repeated in two places at two precisions.** Each figure in this document was matched
   against the report and against `STATO.md`. It found the JVM's CPU share, which appeared as a
   measured-looking "31.4 s user + 14.6 s system … 7.6 % of one core" in both, from the shell's
   `time` around the campaign invocation — **an artifact that does not exist in the repository**.
   Replaced everywhere with the harness's own `threadCpuNanos`, which every sample carries
   (140.6–658.0 ms per 8 000 ms window, median 276.3 ms = 1.8–8.2 % of one core). The same sweep
   caught a figure with only half an artifact behind it: the arrival script's "226 over its whole
   horizon" was a replayed count with no committed method, and the paragraph now makes only the
   claim the analyzer prints (`offered` identical in 27 of 30 pairs, three differing by one, cause
   named per pair).
5. **Evidence that does not reproduce.** Every `git grep` cited in this document was re-run with a
   concrete pathspec, because one of them was quoted from a pattern that matches nothing at all:
   `'platform/*/src/main'` under `git grep` returns zero for *any* term, which the sweep confirmed
   with a term that certainly exists. That is what §5.7 had leaned on. Its replacement is a grep that
   reproduces (`EngineReadiness`: 0 `.java` files at `05f49dcb`, 15 at HEAD; `notReady`: 0 at OLD),
   and the section now says what *does* exist at OLD rather than denying it exists. The §5.2 expiry
   grep was checked the same way and passes — it uses a concrete path and returns 0 files for the
   async module against 5 for the sync module, which is the asymmetry M8 rests on.

**What the sweep found that the fix round did not name:** the two scope errors of §8/§9.1, the
`queued` throughput suggestion that had been left implicit, the second mechanism behind the offered
differences (two of the three differing pairs have *equal* retry counts, so the retry draw explains
one pair and not the other two), and the CPU figure with no artifact. The sweep also confirmed three
things rather than changing them: the `saturated` numbers, the conservation tally and the settlement
table are all exactly as the tool prints them.

**One sweep found nothing and that is worth recording too:** no figure in either document is quoted
to a precision the artifact does not carry — every percentage here is the tool's own two decimals, and
the one place a value is stated as a range is labelled as one.

### The reconciliation, and the coverage it reaches

Round 1 swept for the *class* of "prose outrunning the artifact" in five themes and still missed
things — including a table cell that paired one run's `completed` with another's `useful`. The
diagnosis is not that prose drifts; it is that **verification was partial**, and the one place this
document was checked by machine (its largest table) was the one place nothing was ever found. Round 3
added the mechanism that made the earlier rounds miss: the inventory scanned **digits**, so every
number spelled out in words — "two of the six profiles", "the three that differ" — was invisible to
both the registry and the inventory, and every falsehood this round fixed was written in words.

So the sweep is replaced by a committed reconciler, `reconcile-12e.py`, whose output is
`raw/reconciliation-12e.txt`.

**The exemption, stated where a reader of this document can see it.** This section is the one place
the reconciler's *inventory* exempts, and the reason is that its numbers are claims about the
reconciler's own run rather than about the artifact. They are not therefore unchecked: a second
registry — the instrument registry — is evaluated against the whole document, this section included,
and it recomputes every count below from the run. That is why the table count here is **13** and not
the 11 this section said before the round that added two tables, and why a stale number in this
section fails the reconciler exactly as a stale figure in §9 does.

| what | how it is reconciled | coverage |
|---|---|---|
| every cell of all 13 tables — the 12 of them this reconciler checks, the thirteenth being this table | recomputed from `raw/old-vs-new.jsonl`, `raw/smoke-old.jsonl`, `summarize.workload_table` or the analyzer's own output | **797 table cells**, 0 mismatches; 13 cells are names, types or declared omissions |
| tables quoted verbatim from the analyzer | **string equality** against the analyzer's own output | the settlement, comparison, medians, resolving-power and per-workload-resolution tables |
| numeric claims in the prose | a registry of **142 entries**, each recomputing its value from an artifact or from a declared literal | **148 of 148** occurrences matched and recomputed, 0 mismatches |
| numbers spelled out in words | a second registry, the same discipline: a stale entry fails the run | 5 word claims recomputed |
| prose numbers that are not claims | 18 named classes for digits, and a parallel set for words, each printed with an example so the filter can be audited | 289 + 149 numbers |
| what is left | listed in full, never truncated | **0 numbers** left over |

Of the registry entries, **2 of them declared literals**: their value cannot be recomputed from an
artifact (the count of post-campaign lines dropped from the load record, and the saturated profile's
service rate, which lives in `SchedulerSwitchBenchmark.Profile` rather than in the artifact), and
each declares itself as such in the entry rather than hiding among the recomputed ones. Every other
entry recomputes. That sentence was false until this round: four further entries returned bare
constants while declaring themselves recomputed — the two pair-set sizes of §9.2, the ticket size
the differing offered pairs differ by, and the arrival count on the script's horizon — and an
instrument whose own account of itself is wrong is the failure this document is written against. All
four now compute their value from `raw/old-vs-new.jsonl` or from a replay of `Script.build`, and a
perturbation of any of them fails the run.

**The tolerance every check here carries, stated because a reader cannot otherwise know it.** Each
comparison accepts a figure within a flat absolute tolerance of ±0.011 of the artifact's value —
that is slack, not rounding, and it is the same for every claim regardless of the precision it is
written at. A figure carrying two decimals is therefore checked no more tightly than that, which for
the finest claims in this document — a resolution of a hundredth of a percentage point, or the heap
deltas' hundredths — is a large *relative* error. `TOLERANCE` is one named constant in the script
that both the checks and this sentence are read from, so the two cannot drift apart. It exists
because the document rounds and a two-decimal percentage cannot be matched exactly; it is stated
here because an unstated tolerance is exactly the slack that let a heap resolution pass while
reading a column that does not contain it (see the coverage limits below).

**The instrument's bite is itself measured, not asserted.** `reconcile-12e.py --perturbations`
applies twelve changes a careless edit could make and requires the reconciler to fail on every one;
it first asserts that the **unperturbed** document passes, because twelve `caught`s out of a document
that was already failing measure the environment rather than the checks. Its output is
`raw/reconciliation-perturbations.txt`, and **all 12 perturbations caught** is a computed claim like
every other count here. Three of the twelve are the defects round 3's fixes removed (the grep count,
a cell of the table quoted verbatim, and the cross-artifact `completed / useful` cell); the rest are
the mechanism's own holes — a value swapped to another row of the analyzer's table, a
metric-dependent count written in a word, a claim written in words, a settlement-table cell, an
instrument count, a range endpoint, this section's own per-class totals, and this count in words. The self-test was what
found that the MISS's own `+5.40 %` had no registry entry at all: the perturbation for it failed to
apply, which is a check being stale rather than a check passing.

A table the reconciler has no checker for **fails its run**, so adding a table to this document
requires adding a checker; a registry entry whose claim has been reworded away **also fails**, so a
stale pattern cannot silently stop guarding anything; and a word-form claim is guarded whether it is
written as a digit or as a word. All of those failed repeatedly while this section was being written,
which is the point.

The two inventories are reported separately because they are not the same size or the same kind:
**prose numbers NOT reconciled and NOT a
citation: 0**, and **words NOT reconciled and NOT a
citation: 0**. Their classes are separate too — the digits fall into 18 named classes for digits and
a parallel set for words carrying 8.

**One class is deliberately weaker than the rest** and is labelled as such: the numbers that are
neither a registry claim nor a citation are classified as "a value the analyzer also quotes", which
checks that the figure exists in the analyzer's output — **not** that the sentence attributes it to
the right workload or metric. A value swapped from one row of the analyzer's table to another would
pass that class, so it is counted separately, never folded into the strong classes, and the residue
below is listed rather than summarised.

**The residue is now zero, and that is a stronger statement than it looks — with one caveat kept
visible.** Round 3's word pass and its subject check closed the two holes that let earlier numbers
pass: figures spelled out in words, and figures that exist in the analyzer's output but belong to a
different row. Every prose number and every spelled-out number in the non-exempt document is now
either recomputed by a registry entry or classified as a citation, reference, code or ordinary prose.

The caveat is that the classes are not all equally strong, and one of them is deliberately weaker. A
number classified as "a value the analyzer also quotes for the subject its clause names" has had
**both** its value and its subject checked — but only against the analyzer's table, not against the
sentence's own arithmetic; a number classified as an article, a pronoun or an ordinary noun is not a
claim at all; and a citation is a pointer, not a figure. Both sets of classes — the digits' and the
words' — are printed with their counts and an example each in `raw/reconciliation-12e.txt`, so which
class a given number or word fell into is auditable rather than asserted. (Until this round the
sentence above was true of the digits only: the word pass reported a total and nothing behind it,
which is the difference between an auditable filter and a claim.) Two registry entries declare
themselves **literals**, because their value cannot be recomputed from an artifact at all.

### What this instrument was demonstrated not to catch

Every limit below was **demonstrated** — someone made the change and the reconciler still returned
PASS — rather than argued from the code. They are stated here rather than closed, because an
instrument whose holes are known is usable and one that claims to have none is not: these are the
edges of what a PASS above means, and they are parked for the whole-branch review rather than
patched one at a time.

1. **A count spelled out in words is not a claim.** Changing "post-GC heap (five)" to "(two)"
   returns PASS: the digits are guarded by registry entries, their word restatements are guarded by
   the word pass only where an entry names them, and everything else falls to the classifier's
   "prose enumeration or count of things in this document" class, which is a citation class and
   checks nothing. The same class let a spelled-out count survive inside the very sentence round 3
   rewrote to fix a spelled-out count.
2. **A figure whose clause names no workload falls to the weakest class.** Changing §9.2's `queued`
   confound range from "1.0 % to 2.5 %" to "3.5 % to 4.5 %" returns PASS: both strings exist as
   substrings of the analyzer's output, so "the analyzer also quotes this figure" accepts them, and
   with no workload and metric word in the clause the subject check never runs. The window it looks
   through is the line, not the paragraph — the subject has to be named in the clause itself.
3. **A figure bracketed by a metric name short-circuits the subject check.** Appending "On p99 the
   pairing cuts the spread to a hundredth of a percent." returns PASS although it is false: p99's
   paired spreads are **13.66 %** (whole-span) and **11.96 %** (steady), per §8. `p99` in the
   preceding text matches the citation classifier's metric-name class, which returns before the
   subject test is reached.
4. **A whole inserted sentence of false prose passes.** Appending "Three of the six profiles are
   expiry-confounded." returns PASS although **two** are — `queued` and `saturated`, as §7.2's
   `policy-confounded` column and the very pair-set table the sentence would be checked against both
   say. A sentence of prose whose numbers sit in no registry entry and match no citation class is
   exactly what the classifier calls ordinary prose, and the classifier does not read.
5. **The tolerance is flat and absolute.** Every comparison carries the same slack, stated above,
   whatever precision the claim is written at — so a two-decimal claim, or a delta written as a
   hundredth, is accepted with up to that error, which in relative terms is a large one for the
   finest claims in this document. A **range endpoint** is the case to watch, because the endpoints
   are what the ranges in §9.1 turn on: writing the five-profile steady range as `0.01–43.48 %`
   rather than `0.00–43.48 %` — which would change which set the sentence describes — is inside the
   slack and returns PASS. The tolerance is stated above for this reason; it is not a substitute for
   a check.

Three further bounds on how this may be used, each of them a property of the script rather than of
the document:

- **It only means anything inside a checkout of this repository.** Six entries are `git grep`
  claims, run from the repository root; outside a checkout they have nothing to run against. The
  script now **refuses to start** outside one instead of reporting those six as uncomputable.
- **`WORD_VALUES` stops at "twelve".** A count spelled above twelve is invisible to the word pass —
  the same hole as limit 1, one word past its table.
- **`--perturbations` asserts the unperturbed run passes before applying anything.** It did not
  until this round, so an environment whose document was already failing reported its `caught`s as
  though they were checks; that precondition is now the first thing it applies.
