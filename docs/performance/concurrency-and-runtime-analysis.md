# Concurrency control and runtime footprint: what was measured

August 2026. Every figure here comes from a run in this repository's harness;
where a claim was made and later refuted by measurement, the refutation is kept
rather than the claim.

## Summary

1. Under a load calibrated to fill the queue, the `BUDGETED` and
   `ADAPTIVE_PER_POD` controllers are within 1% of each other end to end.
   `BUDGETED` reaches it with substantially less concurrency on the function that
   does not need it, which is the property the mode exists for.
2. The controller optimises a quantity the caller does not experience. Service
   time is roughly a seventh of end-to-end latency under queueing, so a governor
   can sit comfortably inside a 10ms SLO while callers wait 80ms.
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
by service time alone. That change has not been made.

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

**Known regression:** under G1 the native image exposes no GC metrics at all —
not even the polled MXBean counters that work under the serial collector. The
best configuration is the one we are blind on.

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
- Whether driving the controller from queue wait rather than service time would
  fix the mismatch in §2. It is the obvious next experiment.
