**Outcome of the control-plane correctness and performance campaign — September 2026**

Third of a trio: [the review](control-plane-review-2026-09-05.md) found the
problems, [the plan](plans/2026-09-05-control-plane-correctness-and-performance.md)
decided what to do about them, and this records **what actually happened** —
including the parts the plan did not foresee and the decisions taken against the
initial intuition.

It does not repeat what Git history already carries (who changed what, when). It
keeps the *why*, which code cannot: the alternatives discarded, the measurements
that refuted a reasonable thesis, and the questions left open.

Released as **v0.21.0**.

---

## 1. The worst defect, and why nobody had seen it

A replay carrying an idempotency key, after its outcome payload had been evicted
for capacity, **re-invoked the function** instead of answering `410`. That is
precisely the guarantee the key exists to give.

The mechanism: `InvocationEnqueueSupport.admitIfNew` dispatches **first** and
publishes the claim **after**. When the execution settles inside that admission
action, `markTerminal` finds the key still `pending` and no-ops by design; the
claim published immediately afterwards then stays non-terminal forever.

In the no-queue profile this is not a race but **deterministic behaviour**:
`admitLocally` dispatches inline on an already-completed future.

Why it escaped: every idempotent-retention test called `publishAdmission()`
*before* settling the execution — the reverse of the production order. They built
the happy sequence by hand, and none exercised the real one.

> **Reusable lesson.** When a test constructs the order of operations by hand,
> check that it is the order production actually produces. Here, comparing the
> test against `admitIfNew` was enough.

## 2. Metrics that feed a control loop

M1 had redefined the end-to-end conclusion, but four terminal paths recorded none
at all: the sync queue's wait timeout, a function removed while its work was
queued, and both offload conclusions.

This is not a coverage gap. Those are **exactly the overload populations**, and
`SojournConcurrencyController` steers concurrency from that timer. Censoring them
made it read an optimistically low sojourn precisely when it had to react.

The fix does not add calls to the individual paths: it hangs the conclusion off
the **store's terminal listener**, the only event common to every terminal
policy — and the sync-queue module, which owns two of those paths, has no access
to the completion handler at all. A future terminal path is covered without
anyone having to remember it.

## 3. Three of six tuning interventions were rejected

This is the most useful result of the tuning phase, and the plan prescribes it:
when the benefit does not exceed measured variability, document it and keep the
previous default. Full detail and raw data in
[`experiments/control-plane-tuning-2026-09/`](experiments/control-plane-tuning-2026-09/RESULTS.md).

| Intervention | Outcome | The number that decided it |
|---|---|---|
| Explicit HTTP provider with a lifecycle | adopted | not a performance case: the global provider was **never** disposed |
| Shorter acquisition timeout | **rejected** | with the dispatcher's cancellation, 45 s / 5 s / 1 s are indistinguishable |
| Per-function queue depth counters | adopted | enqueue under scrape 8,739 → 305 ns |
| Single-monitor rotation | **rejected** | rotation 7.5× faster, concurrent enqueue 2–9× slower |
| Async batch 4/8/16 | **rejected** | indistinguishable from 2 on throughput and p99 |
| Outcome budget in bytes | adopted | heap 1,279 → 6 MB with 64 KB payloads |
| Global metrics lock off the hot path | adopted | 8 threads: 2,638 → 786 ns |

Two deserve a note, because the reasoning outlives the verdict.

**Single-monitor rotation** looks like an obvious win: 65 monitor acquisitions
instead of one. Collapsing them makes the rotation 7.5 times faster **and starves
admission**, which can no longer interleave between short critical sections.
Rotation is maintenance; admission is the caller's path. The javadoc now says so,
to stop it being "optimised" again.

**The outcome budget** was documented as "about 12 MB" — true for a compact
116-byte outcome, false for a *readable* one that retains the caller's payload.
Measured, 20,000 outcomes at 64 KB hold **1.28 GB**, and at the shipped default
of 100,000 that would be roughly 6 GB: the same shape as the 2026-08-23 incident
the store cites in its own javadoc, and one the count cap does not prevent.

## 4. The finding only real load could show

The `concurrency-cycle-container` scenario was failing. Bisected across seven
revisions, the commit that flips the outcome is **P1**, "concurrent container
proxy".

It was not a regression. P1 made `RoundRobinFunctionProxy` concurrent, and **that
serialization was the degradation the governor had been reacting to**: 857 →
3,387 req/s, p95 60.01 → 14.52 ms. At eight concurrent requests the function now
answers in 1–3 ms without degrading, so the governor holds its ceiling —
correctly — and the scenario reads that correct behaviour as a failure.

> **Three reviewers, 536 unit tests and the whole validation matrix had not seen
> it**, because none of those checks puts the control plane under real load. It
> is the strongest argument for sections 7 and 8 of the plan.

The mirror image is just as instructive: before P1 the scenario **failed the SLO
thresholds** (p95 60 ms) while the governor regulated. The governor regulated
*because* the platform was slow.

## 5. Two measurement errors with one root cause

Both produced large, coherent, **wrong** numbers.

**T1.** The first harness measured 62% of backend work wasted and a p95 at twice
the budget. But it never cancelled the request, while `ExternalDispatcher` wraps
it in `.timeout(functionTimeout)`, which cancels the pending acquisition too.
Re-measured, the gain disappeared entirely — and the previous default stayed.

**T3.** The first harness reused one `String` instance for every outcome: the
heap held a single payload, and large ones looked free.

> **Reusable lesson.** Before measuring an intervention, reproduce what the real
> caller does to the call. Both runs are kept under `raw/`, the refuted one
> included, because the reason it was wrong is the part needed again.

## 6. Baseline against candidate

Three repetitions per arm, alternated, same machine (NVIDIA DGX Spark, aarch64),
same scenario, same corpus.

| metric | v0.20.0 | v0.21.0 | delta |
|---|---|---|---|
| throughput | 350.1 req/s [346.3–366.7] | 849.9 [845.0–862.5] | **+142.7%** |
| p50 | 113.2 ms | 39.9 | −64.7% |
| p95 | 143.9 ms | 68.6 | −52.4% |
| p99 | 152.7 ms | 77.6 | −49.1% |

No dispersion overlap on any metric.

Two readings not to get backwards. The baseline's failure does **not** mean it
was broken: the thresholds are calibrated for the candidate and it misses them by
being slower. And the baseline's lower governor floor (2 against 5) is **not** a
better governor: it has to give up more concurrency to survive the same load.

## 7. What remains open

- **Did RAM usage grow?** We do not know. The medians point up but the dispersion
  swamps them, and `heap min` is a noisy proxy for the live set. Recorded in
  [#207](https://github.com/miciav/nanofaas/issues/207) with the soak design that
  would settle it.
- **512 MB is not enough** for the control plane under the comparison profile:
  `OOMKilled`, verified on the pod. Not new — the earlier campaign already showed
  the same restarts without recording them as such.
- **Section 7 rows not executed**: the Kubernetes provider, and a real native
  build plus smoke. Executable, not executed.
- **Section 8 partial**: missing the ASYNC profiles, keyed replay, small payloads,
  errors/retries, and the long soak.
- **Experiment B** from the previous plan (JIT and event loops): not run, 12 cells
  each with an image build inside the VM. The `jvm-loop1` and `jvm-c2-loop1`
  variants that were then missing from NanoLab **now exist**; only the cost
  remains.

## 8. A note on method

Half the fixes in this campaign came from code review, the other half from
measurement. The two are not interchangeable, and each found the other's blind
spots.

Review found the idempotency defect, the censored metrics and the `PATCH` that
never reached the proxy — all invisible to a benchmark, because the system
*appears* to work.

Real load found what review could not: that a performance change had made
obsolete the very scenario meant to verify it. No amount of reading the code
would have shown that.

And in two cases out of three, measurement **refuted** the starting intuition. A
knob that looked obvious bought nothing; an evident optimisation made the path
that matters worse. Worth remembering the next time a change seems too obvious to
measure.
