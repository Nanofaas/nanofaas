# Concurrency control and runtime footprint: what was measured

August 2026. Every figure here comes from a run in this repository's harness;
where a claim was made and later refuted by measurement, the refutation is kept
rather than the claim.

## Summary

1. Under a load calibrated to fill the queue, the `BUDGETED` and
   `ADAPTIVE_PER_POD` controllers are within 1% of each other end to end.
   `BUDGETED` reaches it with substantially less concurrency on the function that
   does not need it, which is the property the mode exists for.
2. The controllers optimised a quantity the caller does not experience. Service
   time is roughly a seventh of end-to-end latency under queueing, so a governor
   can sit comfortably inside a 10ms SLO while callers wait 80ms. Deciding from
   sojourn time instead served 3.2% more requests, refused 15.7% fewer and cut
   end-to-end p95 by 10.6% — but only under open arrivals, and only after two
   earlier designs failed.
3. Co-tenancy cross-talk is real but is not CPU contention. Pinning both
   functions to the same four cores changed nothing measurable.
4. A natively compiled control plane loses half its throughput to garbage
   collection pauses on GraalVM Community, and recovers it entirely with G1 on
   Oracle GraalVM.
5. Memory figures without a container limit describe appetite, not need.

---

## 1. The two controllers, compared fairly

**Setup.** Two functions on one control plane, pinned to the same four cores,
queue of 100 each, per-function ceiling 8, `BUDGETED` total budget 12. Closed-loop
load peaking at 103 offered requests per function across three windows: one
function alone, then the two in antiphase, then the two in phase. Both modes were
offered exactly the same load.

| | ADAPTIVE_PER_POD | BUDGETED |
|---|---|---|
| requests | 899,684 | 893,841 |
| refused | 7 (0.001%) | 33 (0.004%) |
| end-to-end p95 | 80.2 ms | 79.7 ms |
| end-to-end p99 | 90.2 ms | 87.2 ms |

**Granted concurrency, by window.**

| window | ADAPT. java | ADAPT. lite | sum | BUDG. java | BUDG. lite | sum |
|---|---|---|---|---|---|---|
| one alone | 7.8 | 4.1 (idle) | — | 8.0 | 1.2 (idle) | — |
| antiphase | 8.0 | 5.0 | 13.0 | 8.0 | 2.9 | 10.9 |
| in phase | 7.9 | 5.5 | 13.4 | 8.0 | 3.7 | 11.7 |

`BUDGETED` holds the total under its budget and moves capacity between the
functions as the load moves; `ADAPTIVE_PER_POD` has no mechanism to do either,
because neither of its two independent controllers knows the other exists.

The asymmetry between the two functions is **not** unfairness. They are different
runtimes (see §4) and `lite` serves in 2.15ms against 5.57ms, so Little's law
gives it a smaller limit: at 1,493 requests per second it needs about 3.2 in
flight, and more buys no completions. `BUDGETED` granted it 2.6 on average
against `ADAPTIVE`'s 4.9 **at the same throughput**, and its service time fell
37%. Half the concurrency, the same work done.

### A calibration error worth recording

The first run of this comparison set the budget to 8 while the load peaked at 105
against a queue of 100. Under contention each function held about 4 of 104 places
against 105 arrivals, so the queue overflowed by arithmetic in one mode and not
the other, producing 86,302 rejections that said nothing about the controller.

The budget has to sit inside a window: at or above `functions x ceiling` nothing
is ever scarce and the mode degenerates into per-function control; below
`functions x (peak - queue)` the rejections are manufactured. The offered peak
must be derived from the smallest limit any mode under comparison will grant,
or the two runs are not measuring the same thing.

## 2. The queue is where the latency is

`function_latency_ms` is measured from `startedAt` to `finishedAt` **by the
control plane**: it is the dispatch round trip, not the function's internal
execution time. The governor decides from it.

Under the burst profile the mean queue wait was 37–43ms against a service time of
about 5ms. The quantity the controller steers by is roughly **a seventh** of what
the caller experiences.

The consequence is measurable rather than theoretical. In an early comparison
`BUDGETED` cut service time by 31% on one function and 44% on the other — both
far inside a 10ms SLO — and **made end-to-end p95 worse**, because the queue wait
its own limit created more than absorbed the gain. A concurrency limit does not
remove work; it moves it into the buffer.

This suggests the controller should be driven by queue wait, or by the sum, not
by service time alone. That change was made, and §2b is what it cost and bought.

## 2b. Deciding from the caller's latency: two failures and a result

The `SOJOURN` mode decides from sojourn time — wait plus service — instead of
service time. Three designs were needed and the two that failed are worth as much
as the one that worked, because each fails for a reason that is easy to repeat.

**Feeding end-to-end latency into the existing gradient rule** is unstable, and
was rejected before implementation. That rule shrinks the limit when latency
exceeds its target; a smaller limit drains the queue more slowly, so the wait
grows, so it shrinks again, to the floor.

**Searching for the minimum** — step, compare with last time, carry on if better
and turn if worse — was implemented and falsified by measurement. Sojourn does
have an interior minimum where service time has none, so the search is
well-posed in principle. It fails because it cannot tell "my move helped" from
"the load fell". In a trough it credited the natural relief to its own decision
to shrink and was still small when the load returned:

| | ADAPTIVE_PER_POD | SOJOURN, searching |
|---|---|---|
| refused | 32 (0.004%) | **36,181 (4.275%)** |
| limit | 7.8 [4-8] | 4.0 [1-8] |

The operating point it chose was not the problem — at limit 4 it delivered 96.5%
of the throughput with half the service time. What it missed is that **the limit
does two jobs**: with the queue it is also the admission window, since a function
holds `limit + queueSize` and refuses the rest. Sizing it from latency alone shed
4% of the traffic.

**Computing it from a model** works. Three parts in strict order of authority: an
admission guard that raises the limit whenever the queue passes its high-water
mark and forbids lowering it; Little's law on a *predicted* arrival rate (Holt
with a damped trend, because a moving average lags a ramp and a ramp is where the
queue builds); and the end-to-end target shortening the drain horizon in
proportion to how far outside the promise the function is. Nothing compares two
intervals, so the attribution problem cannot arise.

### The closed loop could not pose the question

Under `burst` each VU holds one request, so the number in the system is pinned by
the generator and queue depth is `VUs - limit`. Two consequences, both measured.
Raising the limit from 4 to 8 shortened a 99-deep queue by four — no controller
can move the wait by much. And the queue sits at 95 of 100 by construction,
permanently above any high-water mark, so the admission guard fires every tick
and the model never speaks: the re-run reads `8 [8-8]` for its whole length.

That is why two controllers reading entirely different signals landed within 2%
of each other on this profile. The similarity was a property of the harness.

### With open arrivals

Arrivals scheduled by the clock at a peak above measured capacity, so the queue
grows from demand. Same load, same cores, same queues, both modes per-function.

| | ADAPTIVE_PER_POD | SOJOURN | |
|---|---|---|---|
| offered | 659,973 | 659,995 | identical by construction |
| **served** | 549,856 | **567,210** | **+3.2%** |
| refused | 110,117 (16.7%) | **92,785 (14.1%)** | **-15.7%** |
| **end-to-end p95** | 120.48 ms | **107.68 ms** | **-10.6%** |
| **end-to-end p99** | 157.67 ms | **133.82 ms** | **-15.1%** |
| queue wait | 35.61 ms | 31.03 ms | -12.9% |
| service time | 1.69 ms | 2.00 ms | +18% |
| mean limit | 3.9 | 2.8 | |

The trade went the predicted way: service time worsened 18%, the wait improved
13%, and since the wait is twenty times the service time the caller gains. The
target — same throughput, same rejections, lower end-to-end p95 — was written
into the scenario before the run, and all three were beaten.

The instructive detail is that SOJOURN did this with *less* mean concurrency.
It does not win by adding capacity but by placing it in time, which is what the
forecast was there to buy.

**Caveats.** One run each, unrepeated: differences of 10-15% are large but not
established. Both systems are shedding 14-16%, because the open-loop peak
genuinely exceeds capacity — this compares two overloaded systems, not two
comfortable ones. `dropped_iterations` was 21 and 0, so the generator was not
giving up and the refusals are the platform's.

## 3. Co-tenancy: cross-talk without contention

Two functions on one control plane, one holding steady while the other comes and
goes. When the neighbour arrives, the steady function loses 28% of its throughput
and gains 36% of latency.

The obvious explanation was CPU contention. It is wrong. `nanofaas.container-local.cpuset`
was added to pin both functions to the same four cores, and the run was repeated:

| | own 4 cores each | shared cores 0-3 |
|---|---|---|
| java alone | 1762 rps / 4.57 ms | 1762 rps / 4.57 ms |
| java with neighbour | 1274 rps / 6.23 ms | 1274 rps / 6.23 ms |
| combined during overlap | 2769 rps | 2751 rps |

Identical to three significant figures. Constraining both functions to four shared
cores costs 0.6% of combined throughput, which is noise. The four cores were never
the binding constraint: at ~2,760 requests per second each request can cost at
most 1.45ms of CPU, and they achieve it. The cross-talk comes from what the two
share in **both** configurations — the single control plane — not from the cores.

The `cpuset` support is worth keeping regardless, because it makes the concurrency
budget a division of capacity that exists.

## 4. The two `word-stats` functions are not the same runtime

Recorded because scenario comments claimed otherwise for some time.

| | `word-stats-java` | `word-stats-java-lite` |
|---|---|---|
| build | Spring Boot on a JVM | GraalVM native binary |
| SDK | `sdks/java` | `sdks/java-lite` |
| resident, idle | 133 MiB | 2.7 MiB |
| resident, warm | 160 MiB | 51.6 MiB |
| service time | 5.57 ms | 2.15 ms |

Any experiment treating them as interchangeable is confounded.

## 5. Where a Spring JVM's memory goes

For `word-stats-java` at 190 MiB resident:

| area | |
|---|---|
| heap in use | **22.4 MB** |
| Metaspace (9,375 loaded classes) | 53.0 MB |
| Compressed class space | 6.6 MB |
| CodeHeap (JIT-compiled) | 13.5 MB |
| 23 thread stacks | ~23 MB |

The heap — the data — is 12% of the total. The rest is the runtime describing
itself: class metadata, compiled code and the profiles kept to recompile it.
Spring is expensive in memory because it loads thousands of classes and because a
JVM carries a compiler, not because it allocates.

Compiling the same function natively takes idle resident memory from 133 MiB to
25.6 MiB and startup from 1.046s to 0.066s. Under load, the natively compiled
Spring function (56 MiB) and the lite native function (51.6 MiB) nearly converge,
though they differ tenfold at idle: the warm working set is dominated by
per-request allocation, not by the framework.

## 6. The native control plane and its garbage collector

A native control plane at first appeared to halve both memory and CPU. It had
halved the **work**: 415,184 requests against 865,692, with p99 at 1,696ms.

Measured in isolation — same backend, same load, only the control-plane build
differing:

| | JVM | native (serial) | native (G1) |
|---|---|---|---|
| throughput | 8,205 rps | 4,983 rps | **8,085 rps** |
| median | 5.98 ms | 4.71 ms | 6.08 ms |
| p95 | 16.06 ms | 27.95 ms | 16.51 ms |
| **maximum** | 92 ms | **1.77 s** | **123 ms** |
| startup | 1.585 s | 0.192 s | 0.072 s |

The native build's **median is better than the JVM's**. Between pauses the AOT
code is fast; the loss is entirely tail. CPU cost per request is 1.62 against
1.32 ms — the ordinary AOT penalty of 23%, nowhere near enough to explain a
halving — and the system was using 265% of 1,100% available CPU while delivering
half the throughput. Nothing was saturated; something was stopping.

With the GC binder fixed (`JvmGcMetrics` needs notifications that SubstrateVM
does not emit, and reported a flat zero), the cause was direct:

| | JVM | native (serial) |
|---|---|---|
| complete collections | 2, ~50 ms each | **25, 883 ms each** |
| total time collecting | 6.6 s | **22.1 s of 60** |
| share of wall clock | **0.9%** | **31.8%** |

Three hypotheses were tested and rejected:

- **Bounding the heap.** `-Xmx512m` made it far worse: 1,543 rps, 30s timeouts.
- **Premature promotion.** Enlarging the young generation changed nothing. (The
  first attempt at this test was invalid: `-XX:MaxNewSize` is capped by
  `-XX:MaximumYoungGenerationSizePercent`, default 10, so the flag did nothing.)
- **Netty heap buffers.** Both builds prefer direct buffers, both use the
  `adaptive` allocator, both lack `Unsafe`. Identical.

The pause scales with heap **size**, not with live data: 563ms for a 512MB heap
against a live set of tens of megabytes. That is a collector walking the whole
space, which is what GraalVM Community's only usable collector does — `--gc`
accepts `serial` and `epsilon`, and nothing else.

G1, available in Oracle GraalVM, removes the problem entirely: 98.5% of the JVM's
throughput, a tail of 123ms, and a 0.07s start. Oracle GraalVM is GFTC-licensed
rather than GPL, so the build stays opt-in via `NATIVE_GC=G1`.

### Observing the collector on each build

Under G1 the native image registers no `GarbageCollectorMXBean` at all, so
neither Micrometer nor the polling binder reports anything — and
`--enable-monitoring=jfr,jvmstat,jmxserver` does not bring them back. That was
tested and does not work.

JFR does, by an indirect route. The GC-specific event types
(`jdk.G1GarbageCollection`, `jdk.GCHeapSummary`, the `GCPhase*` family) are
declared in the recording and emit zero events; stopping there would suggest JFR
is useless here. The pauses are recorded as **VM operations** instead, with a
duration each. From a 90s recording under load:

| operation | count | total | mean | max |
|---|---|---|---|---|
| `G1 wrapper` | 43 | 835.1 ms | 19.4 ms | 97.6 ms |
| `Collect for allocation` | 31 | 651.1 ms | 21.0 ms | 94.5 ms |
| `Try init concurrent mark` | 2 | 37.3 ms | 18.6 ms | 22.5 ms |
| **total** | **76** | **1,524 ms of 90 s** | | **1.69%** |

That independently confirms §6 by a different route — 1.69% of wall clock against
the serial collector's 31.8% — and yields more than the MXBeans ever could: a
duration per pause, and therefore the maximum, which the polled counters cannot
give even where they work.

| build | counts and total time | maximum pause |
|---|---|---|
| JVM | Micrometer, natively | yes, from notifications |
| native, serial | the polling binder | no |
| native, G1 | **JFR only** | **yes, per pause** |

So G1 is not unobservable, it is unobservable **through Prometheus**: the data
needs a recording pulled and analysed out of band rather than a gauge to scrape.
Adequate for an investigation, not for an alert.

### Optimisation level

The build used `-Os` — optimise for size — until it was measured. `-O3` optimises
for speed instead; the levels choose what to spend rather than how much to
optimise, and `-O3` does not reduce image size, it doubles it.

| | `-Os` | `-O3` |
|---|---|---|
| throughput | 8,398 rps | **9,251 rps** (+10.2%) |
| p95 | 15.64 ms | 14.79 ms |
| maximum | 148.7 ms | 127.2 ms |
| startup | 0.173 s | **0.068 s** |
| resident at rest | 120.6 MiB | **39.3 MiB** |
| image | 195 MB | **397 MB** |

At `-O3` the native build does not merely match the JVM, it passes it: 9,251
against 8,205 requests per second, with a start twenty-three times faster.

**Why the image grows**, from the build report: the code area triples, 47.78MB to
166.59MB, out of *fewer* compilation units — 84,694 against 105,256. That pairing
is the signature of inlining. Methods are copied into their callers rather than
called, so units disappear while each survivor carries copies of what it
absorbed, and loop unrolling adds more. Same program, more machine code, which is
also why it is faster: no call overhead and each copy specialised for its site.

**Where the cost lands, and where the gain does not.** With a heap ceiling in
place both levels peaked at the same 744 MiB resident, because the code is mapped
from the file rather than copied into the heap — the extra 200MB is a registry and
disk cost, not a node one. And the throughput gain is conditional: under a 1GB
limit the two builds were identical at ~3,490 requests per second, since there the
bottleneck is collection rather than code quality. `-O3` buys speed only where
memory is not already the constraint.

One further detail worth separating from any future PGO measurement: the build
log reads `PGO: off` at `-Os` and `PGO: ML-inferred` at `-O3`. Oracle GraalVM
applies model-inferred profiles when optimising for speed, so part of this gain is
already a form of profile guidance. Explicit PGO must be measured against `-O3`,
not against `-Os`, or it will be credited with what `-O3` has already collected.

## 7. Memory limits change the answer

All figures above are without a container memory limit, where every build grows
until its collector sees no reason to stop. Under a limit:

| limit | build | throughput | p95 | max | memory used |
|---|---|---|---|---|---|
| 500 MB | JVM | 1,515 rps | 324 ms | 1.99 s | 455 / 500 |
| | native serial | 1,370 rps | 32 ms | 30.0 s | 489 / 500 |
| | **native G1, default** | **520 rps** | 61 ms | 30.0 s | **145 / 500** |
| | native G1, `-Xmx350m` | 1,445 rps | 96 ms | 30.0 s | 378 / 500 |
| 1 GB | JVM | 2,450 rps | 46 ms | 1.34 s | 805 / 1024 |
| | **native G1, `-Xmx700m`** | **3,089 rps** | 44 ms | **135 ms** | 736 / 1024 |
| 2 GB | JVM | 2,712 rps | 47.5 ms | 451 ms | 727 / 2048 |
| | native G1, `-Xmx1400m` | 3,400 rps | 39.5 ms | 151 ms | 1,201 / 2048 |
| none | JVM | 8,205 rps | 16.1 ms | 92 ms | 1.8 GiB |
| | native G1 | 8,085 rps | 16.5 ms | 123 ms | 2.7 GiB |

Two things follow.

**G1 under a small limit is a trap.** Its default ceiling is 25% of visible RAM.
It honours the cgroup, which is right, but then confines itself to 125MB of a
500MB container, leaves 71% of the memory unused and collapses to 520 rps.
Setting `-Xmx` explicitly nearly triples it. **With a memory limit, a native G1
build must be given an explicit heap.**

**At 500MB nothing fits.** All builds lose five to six times their unconstrained
throughput and the native ones start timing out. The remedy there is less offered
concurrency, not a better collector.

The knee is between 1 and 2 GB. 1 GB with G1 and `-Xmx700m` gives 3,089 rps with
a 135ms tail and no failures, in a third of the memory the unconstrained run took.

The compose control plane currently sets `-XX:MaxRAMPercentage=70` with no
`mem_limit`, which on a 12.5GB host permits an 8.77GB heap. **A limit should be
set**; until then every memory figure from that path describes appetite.

---

## What is not established

- Why a complete collection costs 883ms when the live set is small. The pause
  tracks heap size rather than live data, but the mechanism was not confirmed.
- Whether `BUDGETED`'s weights do anything useful: both functions were given
  identical SLOs and weights deliberately, so differentiated service levels
  remain untested.
- Whether the §2b result holds up. It is one unrepeated run per mode, on a bench
  where both systems were shedding 14-16% of the offered load.
- What SOJOURN does when the system is NOT overloaded. Every run of it so far has
  been at or past capacity, so the mode has never been observed choosing a limit
  in comfortable conditions.
- Whether the admission guard's high-water mark at 70% is right. It was picked,
  not measured, and on the closed-loop profile it overrode the model completely.
