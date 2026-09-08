# Phase-4 outcomes (activities T1–T4) and the §7/§8 validation

Method, and what this campaign does not cover: see `README.md`.

---

## T1 — HTTP pool

**Target.** Properties for connections, pending acquisitions and acquisition
timeout; a shared provider with an explicit lifecycle.

**Starting point.** `HttpClientConfig` used `HttpClient.create()`, i.e. Reactor
Netty's **global** `ConnectionProvider`: no stated budget, no owner to dispose it,
and a 45 s `pendingAcquireTimeout`.

### Measurement 1 — without cancellation (`raw/T1-acquire-wait-no-cancel.json`)

Pool 8, concurrency 64, a backend holding each connection for 300 ms, budget
1000 ms. Same capacity in all three arms: the only variable is the acquisition
timeout.

| Arm | p95 | over budget | backend work wasted |
|---|---|---|---|
| 45 s (Reactor Netty default) | 2409 ms | 240 | 240 / 384 (62%) |
| 5 s | 2409 ms | 240 | 240 / 384 (62%) |
| 1 s (= budget) | 1204 ms | 48 | 48 / 192 (25%) |

Read on its own: binding the acquisition to the budget halves p95 and cuts by 80%
the work the backend does for requests nobody is waiting for. It looked like an
obvious adoption.

### Measurement 2 — with the dispatcher's cancellation (`raw/T1-acquire-wait-with-cancel.json`)

**Measurement 1 did not reproduce production.** `ExternalDispatcher:102` wraps the
call in `.timeout(functionTimeout)`: the Reactor operator **cancels** upstream, and
with it the pending acquisition. The harness never cancelled.

Re-measured with that behaviour:

| Arm | p95 | over budget | backend work wasted |
|---|---|---|---|
| 45 s | 1000 ms | 0 | 48 / 192 (25%) |
| 5 s | 1000 ms | 0 | 48 / 192 (25%) |
| 1 s | 1000 ms | 0 | 48 / 192 (25%) |

The three arms are **indistinguishable**. Cancellation already does everything the
acquisition timeout would have done.

### Decision

- **Acquisition-timeout tuning: NOT adopted.** The benefit vanishes once
  production is reproduced, so §6 applies: document the outcome and keep the
  previous default (45 s, Reactor Netty's).
- **Mechanism: adopted**, for reasons that are not about performance and must not
  be sold as such:
  - the global provider is **never** disposed; it is now a bean with
    `destroyMethod`, so its connections do not outlive the context;
  - connections and the acquisition queue become a **stated, tunable** budget
    (`nanofaas.http-client.*`) instead of an inherited default — the "no
    uncontrolled growth of connections and memory" half of the acceptance;
  - the timeout property stays because the cancellation that makes it irrelevant
    belongs to the *caller*: a future dispatch path that forgot to bound its own
    call would inherit the 45 s wait.

### Limit of this measurement

In-JVM, development machine, one backend. It does not cover many destinations or
aggregate connection/memory growth, which is the other half of T1's acceptance and
needs the §8 matrix.

### Method lesson

The first harness produced large, coherent, wrong numbers. What made them wrong was
not a measurement error but a missing model of the caller. Worth repeating for
T2–T4: **before measuring an intervention, reproduce what the real caller does to
the call.**

---

## T2 — Queues: monitors, scans, async batch

**Target.** Profile monitors and scans; per-function counters at the sync queue's
mutation points; a configurable async batch compared at 2/4/8/16; reduce
overlapping locks **only if** the profile demonstrates their cost.

**Shape.** One very active function among 50 quiet ones, depth 200 — the shape the
acceptance names.

### T2a — Per-function scan → counters. **ADOPTED**

`queuedItems(functionName)` was an O(depth) scan under the queue monitor, and
`SyncQueueWorkloadMetricsSource` calls it **once per function** per scrape:
O(functions × depth) with the monitor taken every time.

| Metric | Before | After |
|---|---|---|
| full scrape (51 functions) | 13,111 ns | **312 ns** (42×) |
| enqueue **under scrape** | 8,739 ns | **305 ns** (28×) |
| uncontended enqueue | 288 ns | 295 ns (unchanged) |

Admission under scrape was **30 times** slower than uncontended; the two now
coincide. Counter writes stay inside the existing `synchronized (queue)` blocks, so
the relationship between closure, offer and counters stays atomic; only reads leave
the monitor.

*Limit:* the harness's scraper runs in a tight loop, not at 1 Hz as in production.
The 30× is an upper bound under pathological scraping, not the steady state. The
direction does not depend on the frequency, though: an admission path should not
wait on a metrics read.

### T2b — Single-monitor rotation. **NOT ADOPTED**

`rotateReadyScanWindow` takes the monitor 1 + up to 64 times. Collapsing that into
one critical section looked obvious, and in isolation it is:

| Metric | Per-item (current) | Single monitor |
|---|---|---|
| rotation in isolation | 1,108 ns | **148 ns** (7.5×) |
| concurrent enqueue | **2,274 ns** | 4,262 – 20,668 ns |

But the concurrent enqueue gets 2–9× worse: holding the monitor for all 64 items
means admission can no longer slip between two short critical sections and waits
out the whole batch. Rotation is maintenance, admission is the caller's path: short
acquisitions are the right choice, not an oversight. §8 forbids a >5% worsening on
control scenarios, and this is far worse. Reverted, with the reason in the javadoc
so it does not get "optimised" again.

### T2c — Async batch 2 / 4 / 8 / 16. **NOT ADOPTED**

Dispatch simulated at 50 µs (with an instant dispatch the arms are
indistinguishable by construction, and the first measurement was).

| Batch | wall | quiet-function p99 wait |
|---|---|---|
| 2 | 114 ms | 5 µs |
| 4 | 114 ms | 6 µs |
| 8 | 113 ms | 5 µs |
| 16 | 113 ms | 6 µs |

Indistinguishable. A loop pass costs nothing next to a dispatch, so a wider batch
amortises nothing measurable — and does not worsen fairness either, as one might
have feared. **Default 2 kept.**

The parameter stays injectable (package-private): it is what made the comparison
possible, the campaign's bench uses it, and a future re-measurement should not
start from scratch.

### To carry forward

`POLL_READY_MATCHING_SCAN_LIMIT` is 64 and not configurable. Untouched: there is no
measurement saying 64 is wrong, and changing it without one is exactly what this
phase exists to avoid.

---

## T3 — Memory: weighted payload budget

**Target.** A byte budget beyond the count cap; estimate the weight once, without
re-serializing the payload on every access.

### The risk, measured (`raw/T3-memory-before.json`)

`application.yml` priced the cost as *"a compact outcome measures 116 bytes, so
this cap costs about 12 MB"* with `max-outcomes: 100000`. True for a compact
outcome. But A5 retains the payload for **readable** outcomes (ASYNC or
idempotency-keyed), and there the payload is whatever the caller sent.

20,000 readable outcomes, heap retained after GC:

| payload | heap | per outcome |
|---|---|---|
| 128 B | 8 MB | 427 B |
| 4 KB | 86 MB | 4,518 B |
| 64 KB | **1,279 MB** | 67,098 B |

At the shipped default of 100,000 that would be roughly 6 GB — the same shape as
the 2026-08-23 incident the store cites in its own javadoc (1.05 GB, 50.6% of time
in GC). The count cap does not prevent it.

*Method note:* the first version of this measurement reused the **same** `String`
instance for every outcome, so the heap held one and large payloads looked free.
Same class of error as T1's: the harness did not reproduce what the real caller
does.

### After (`raw/T3-memory-after.json`). **ADOPTED**

`maximumWeight` plus a weigher, default budget `max-outcomes × 116 bytes`.

| payload | before | after | retained |
|---|---|---|---|
| 128 B | 8 MB | 5 MB | 10,357 |
| 4 KB | 86 MB | 3 MB | 553 |
| 64 KB | **1,279 MB** | **6 MB** | 35 |

Heap is flat in payload size: that is the point. The cost is that fewer outcomes
are retained with large payloads — anyone wanting longer retention raises
`max-outcome-bytes`.

### Weigher cost

| shape | ns per call |
|---|---|
| 128 B string | 10 |
| 4 KB string | 2 |
| 64 KB string | 2 |
| map, 64 entries | 105 |

A 64 KB string costs the same as a 128-byte one, because the estimate is
`length()`, O(1): exactly the "without re-serializing the payload" requirement. Map
and collection traversal is bounded in breadth (256) and depth (4), so a
pathologically nested payload costs no more than any other.

### Side effect on tests, and what it teaches

Three tests expressed eviction as a *count* (`max-outcomes: 1`, "one slot"). Under a
weighted budget Caffeine may **reject the newcomer** rather than evict the
incumbent, so those tests depended on which entry it picks — Caffeine's business,
not theirs. Rewritten against an explicit byte budget that makes the scene
deterministic. A5's guarantee is unchanged either way: either the outcome is there
and the replay serves it, or it is gone and the replay answers 410 — the function
never re-runs.

### Not done, and why

T3's acceptance also names an *admission budget for live requests*. Not done: live
requests sit in `inFlight`, already bounded by `maxLifetime` and the concurrency
slots, and there is no measurement pointing at that structure. Adding a budget
without one is exactly what this phase exists to avoid. The long soak crossing the
retention windows needs §8 on NanoLab — see issue #207.

---

## T4 — Diagnostics: meter cost on the hot path

**Target.** Profile meter registration and reads on the hot path; move whatever
does not need atomicity out of the locks. The acceptance is explicit: **the same
observability in both arms**, and do not credit a gain to removing metrics that
steer the governor or the scaler.

No metric was removed here: same meters, same values.

### The defect

`Metrics.metersOrNull` took a `synchronized` block on a **global** monitor shared by
every function, on every call. An invocation crosses it six times: `dispatch`, the
outcome, and the `timers(fn)` lookup preceding each of the three duration samples.
The lock exists to make removal atomic against registration — which concerns the
slow path, not the common one.

### Measurement (`raw/T4-metrics-before.json`, `raw/T4-metrics-after.json`)

ns per hot-path operation, 32 functions:

| threads | before | after | |
|---|---|---|---|
| 1 | 223 | 202 | −9% |
| 2 | 885 | 268 | **−70%** |
| 4 | 2,058 | 928 | −55% |
| 8 | 2,638 | 786 | **−70%** |

Before, the cost *per operation* grew with thread count — 12× between 1 and 8. That
is not a cost, it is a serialization: the work does not grow, the waiting does.

### Decision. **ADOPTED**

The common case (a registered, live function) is a lock-free
`ConcurrentHashMap.get`. The lock stays on the slow path: first registration and
the race with `removeFunction`. The invariant it protects — a removed function does
not re-register its meters — still holds, because registration only happens inside
it, and it is already pinned by
`MetricsTest.removedFunction_doesNotRecreateMetersUntilRegisteredAgain`.

Accepted and documented price: a fast read that grabs the meters an instant before
removal increments a counter about to be deregistered, and that sample is lost.
Serializing every invocation on the platform to keep it would be a bad trade.

The residual growth (202 → 786 ns) is in Micrometer's own meters, which contend when
several threads touch the same function: inherent to the instrument, not to our
lock.

---

## E2E (§7): `concurrency-cycle-container` fails from P1 onward — and it is an improvement

Only executable after fixing NanoLab (see README: a container load-test required a
local environment, and the local executor rejected the `remote_dir` the k6 step
always asked for).

### The symptom

```
'word-stats-java' never gave concurrency back under load:
floor while busy was 8, peak while idle was 8
```

The verifier classes a reading as *loaded* when `in_flight >= effective`, and
requires `loaded_floor < idle_peak`.

### Bisect

| Revision | Outcome | `busy floor` |
|---|---|---|
| `e35405ee` pre-branch | pass 22/0 | — |
| `06095a4d` A7 | pass | **4** (rerun: 5) |
| `711d619f` **P1** | fail | 8 |
| `72fee224` P2 | fail | 8 |
| `36d48600` M1 | fail | 8 |
| `cb22a4b0` M3 | fail | 8 |
| tip post-remediation | fail | 8 |

The commit that flips the outcome is **P1**, "make the container proxy concurrent
and bounded". M1's metrics were the first suspect and the bisect ruled them out.

### Why it is not a regression (`raw/e2e-k6-*.json`)

| | A7 (pre-P1) | P1 |
|---|---|---|
| requests | 334,270 | 1,320,957 |
| throughput | 857 req/s | **3,387 req/s** (+295%) |
| p50 | 44.00 ms | **9.69 ms** |
| p95 | 60.01 ms | **14.52 ms** |
| p99 | 76.29 ms | **35.83 ms** |

Four times the throughput at a quarter of the latency. `RoundRobinFunctionProxy`
serialized invocations towards the replicas; P1 made it concurrent, and **that
serialization** was the bottleneck degrading service time and giving the governor
something to react to.

At eight concurrent requests the function now answers in 1–3 ms without degrading.
The ADAPTIVE controller watches *service-time* degradation: finding none, it holds
the limit at its maximum — the right decision. The surplus queues, and queue wait is
not part of `function_latency_ms`.

Note the mirror image: A7 failed the k6 thresholds (p95 60 ms) in one of two runs,
while everything from P1 on passes the SLO comfortably. The governor regulated
*because* the platform was slow.

### Decision: no platform change

The scenario asserts a property of the governor whose premise was the proxy's
slowness. The premise fell with the bottleneck, deliberately. The retune belongs to
NanoLab and was delivered there (see below); it is a coverage decision, not a
correctness one.

---

## §8 — Baseline against candidate

First run of §8's protocol on this branch. Driver:
`compare-baseline-candidate.sh`; raw per-cell data in
`raw/compare-concurrency-cycle-container/`.

**Arms.** baseline `e35405ee` (the v0.20.0 release point, pre-branch) against
candidate = branch tip.

**Protocol.** Three repetitions per arm, **alternated** within the repetition
(A,B,A,B,A,B) rather than two blocks: if the machine drifts mid-campaign, it drifts
for both arms instead of penalising the second. Same machine (the DGX Spark in the
README), same scenario, same harness, same corpus — verified that
`performance-medium.json` exists at the baseline revision too, or the arms would not
have received the same load.

Scenario: `concurrency-cycle-container` in its retuned form (medium payload, 2
cores, scaled budget). It crosses the container proxy, the sync queue, the
concurrency governor and M1's metrics.

### Results (median, [min–max] over 3 repetitions)

| metric | baseline `e35405ee` | candidate | delta |
|---|---|---|---|
| throughput | 350.1 req/s [346.3–366.7] | **849.9** [845.0–862.5] | **+142.7%** |
| p50 | 113.2 ms [107.2–113.7] | **39.9** [39.1–39.9] | **−64.7%** |
| p95 | 143.9 ms [142.1–145.2] | **68.6** [68.4–68.8] | **−52.4%** |
| p99 | 152.7 ms [152.5–154.1] | **77.6** [77.5–78.4] | **−49.1%** |
| requests served | 135–143k | 330–336k | |
| governor busy floor | 2, 2, 3 | 5, 5, 5 | |
| scenario assertions | failed (3/3) | passed (3/3) | |

**The dispersion does not overlap on any metric**: the candidate's maximum is always
far from the baseline's minimum. With three repetitions per arm that is as clear as
this scale allows.

### Against §8's thresholds

- *"zero functional regressions"* — the candidate passes every scenario assertion 3
  runs out of 3; the baseline none.
- *"at least 10% on the target metric, no worsening beyond 5% of useful throughput
  or p99"* — +142.7% throughput and −49.1% p99. Nothing worsens.

### How to read it, and how not to

**The baseline's failure does not mean "the baseline was broken".** This scenario's
thresholds are calibrated for the candidate: the baseline misses them by being
slower, not by doing anything wrong. The comparison is the numbers, not the
verdicts — which is why the driver archives a cell that fails its assertions instead
of stopping the queue, as its first version did.

**The baseline's lower governor floor is not a better governor.** It descends to 2
against the candidate's 5 because it has to give up far more concurrency to survive
the same load. The candidate's higher limit is the sign that it sustains more work
in parallel.

**This is the delta of the WHOLE branch**, not of a single intervention — which is
what "baseline versus candidate" means for a release decision, but does not satisfy
§8's *"vary one intervention at a time"*. Per-intervention attribution comes from
the bisect above: P1 dominates, measured on its own at +295%.

### Limits

One machine, one scenario, three repetitions. It does not cover the ASYNC profiles,
keyed replay, small payloads, errors/retries, or the soak crossing the retention
windows — all rows §8 lists and that remain to be done.

---

## Experiment A — how much RAM is needed now

A revival of the previous plan's experiment A
(`docs/plans/2026-09-04-overload-path-fixes.md`), this time as a same-machine
baseline-versus-candidate comparison. Data in `raw/expA-memory/`.

Scenarios `runtime-comparison-mem{512,1024}`, variant `jvm-c2`, 3 repetitions per
cell, Multipass + k3s on the DGX Spark. CPU pinned at 2 cores by the scenario to
isolate the memory axis from the CPU knee.

### Main result: 512 MB is not enough, and never was

At 512 MB the control plane is **`OOMKilled`** — verified directly on the pod:
`Last State: Terminated / Reason: OOMKilled / Exit Code: 137`, `Restart Count: 6`,
limit `512Mi`. Not inferred from uptimes.

| | k6 failures | uptime at end of run |
|---|---|---|
| baseline `e35405ee` @512 | 3.0% · 10.6% · 42.6% | 169 / 140 / 223 s |
| candidate @512 | 17.9% · 35.4% · 25.0% | 74 / 50 / 103 s |
| baseline @1024 | 0% · 0% · 0% | 508 / 1023 / 1543 s |
| candidate @1024 | 2.9% · 0% · 0% | 515 / 1040 / 1565 s |

**Both revisions die at 512 MB**, with overlapping failure ranges. Not a regression
from the branch: the earlier campaign already showed 48–170 s uptimes at that limit,
i.e. the same restarts, never recorded as such.

### Identical throughput does not mean "fine"

Both cells report 435.1 req/s. This is an **open-loop** profile: that is the rate
*offered* by the generator, not the rate served. The failure rate is the signal.

### A trap in the data, flagged because it is easy to fall into

`function_success_total.delta` reports −91% for mem512, which is **false**: the
counter resets on every pod restart. With identical throughput and zero recorded
errors the number was incompatible, and cross-checking against `http_reqs` exposed
it. Every cumulative control-plane metric is unusable on a run where the process
restarts.

### Did RAM usage grow? We do not know

At 1024 MB, where neither arm dies:

| metric | baseline | candidate | median delta |
|---|---|---|---|
| heap min MB | 49.9 [38.6–73.2] | 177.5 [42.5–345.0] | +256% |
| heap final MB | 249 [135–457] | 441 [287–582] | +77% |
| heap max MB | 466 [266–486] | 488 [483–641] | +5% |
| CPU max | 0.38 | 0.36 | −6% |

The medians point up, but **the dispersion swamps them**: the candidate's range
contains the baseline's entirely. And `heap min` is the minimum of scraped samples,
hence a noisy proxy for the live set.

Counter-indication: if the candidate needed more memory, it should fail distinctly
sooner than the baseline at 512 MB. It does not.

**Recorded as an open question in
[#207](https://github.com/miciav/nanofaas/issues/207)**, with the soak design that
would settle it: ~90 minutes per arm to cross the retention windows (`ttl 5m`,
`max-lifetime 30m`) several times, sampling heap **after a forced GC** instead of
the scraped minimum, plus the store's counters and the container's RSS.

### Experiment B: not run

`runtime-comparison-cpu1` with 4 variants (`jvm`, `jvm-c2`, `jvm-loop1`,
`jvm-c2-loop1`) × 3 repetitions = 12 cells, each with an image build inside the VM.
The `*-loop1` variants the old `queue.tsv` flagged as missing from NanoLab **now
exist** (`control_plane_variants.py`), so the blocker is gone; only the cost
remains.

---

## Two fixes delivered to NanoLab

Both on `main` of the nanolab repository.

**1. Container load scenarios could not run at all.** `_container_urls` requires a
container load-test to run on a local environment; the local executor rejects any
`remote_dir`; `_build_run_k6` passed one unconditionally. The only environment those
scenarios accept was the one executor that refuses the option they always asked
for — and it failed late, after the images were built and the stack was up.

**2. `concurrency-cycle-container` retuned.** The generator was sending its own
built-in sentences — about twenty words — so the function had nothing to compute and
no CPU cap could create contention (measured: four cores and two cores both gave
1–3 ms). Three changes: `payloadProfile` selects a repository-owned corpus and
finishes the `K6Config.payload_path` / `NANOFAAS_PAYLOAD` wiring that existed but was
never connected; a scenario's per-function `resources` now override the concurrency
default instead of being silently discarded; and the end-to-end p95 budget scales
with the payload profile, keeping the ratio the original calibration used. Result
22/0, repeatable 3/3.
