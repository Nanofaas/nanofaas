# Control-plane correction and optimisation plan

**Date:** 2026-09-05. **Status:** proposed; implementation not started from this plan.

Reference: [control-plane and module analysis](../control-plane-review-2026-09-05.md). The plan covers every problem and suggestion in the report, rate limiter, retry metrics and autoscaling included. Every task ends with verified code, relevant documentation and reproducible results; optimisations change defaults only when the benefit is measured.

The [overload plan of 4 September](2026-09-04-overload-path-fixes.md) already documents work on the WebFilter, JIT and event loop. Check its status in the checkout used for the baseline and keep its settings fixed during this plan's comparisons. The possible Azure experiment C stays in the original plan: it is not a prerequisite for fixing these bugs.

## 1. Expected results and constraints

- Coherent idempotent replays for synchronous and asynchronous calls, before and after archiving.
- Every admitted invocation reaches an outcome; timeouts, scheduling errors and late callbacks leave no unrecoverable local resources.
- A slot is released at most once per attempt, and against the function generation that acquired it.
- Retries configurable even without queue modules; default unchanged at three retries beyond the first attempt.
- Runtime activation/deactivation of the sync queue with no abandoned work.
- A concurrent container proxy, bounded queues and memory, health usable under load.
- Trustworthy metrics for governing concurrency and replicas.
- More successful invocations within the SLO at equal resources, without obtaining throughput merely by raising timeouts or queues.

The project's constraints remain: a control plane with in-memory state, a dedicated scheduler, Java 25, native-image support and no added authentication. The plan introduces neither distributed persistence nor deduplication guarantees across a restart.

## 2. Preparation and baseline — task A0

**Deliverable:** a versioned baseline and reproductions of the defects.

1. Record the commit, the workspace state, the selected modules, the JVM/native configuration, CPU, memory, payload and backend. Do not include unrelated changes in the patches.
2. Make GitNexus available and create the missing local index. If an index already exists, check its freshness and preserve any embeddings. Before modifying each symbol run the upstream impact and report direct callers, processes involved and risk; flag HIGH/CRITICAL before the edits. For extractions or moves add context; for renames use rename with a preview.
3. Move the useful reproductions from `/tmp/nanofaas-audit-0905/` into regression tests in the owning modules. If the temporary files no longer exist, rebuild them from the report's scenarios. Every bug test must fail on the base and pass after the fix.
4. Confirm in tests the findings that are still static: runtime activation of the queue, offload header loss, the rate limiter race, retry metrics and scaling during startup.
5. Collect a short local baseline before the fixes. After the functional corrections collect a second baseline: it will be the reference for tuning, because fixing retries and accounting can change the work actually performed.

The performance matrix does not have to be complete before the fixes start. Avoid concurrency tests built on fragile sleeps: use tickers, steerable clocks, latches and driven completions. Wall-clock times stay in the benchmarks, not in CI's functional assertions.

## 3. Functional corrections

### A1 — Archived asynchronous replay

**Scope:** `InvocationService`, `InvocationExecutionFactory`, `InvocationResponseMapper`, service and controller tests. **Depends on:** A0.

Handle `settledOutcome` in `invokeAsync` before dereferencing the record, reusing the mapping already available for archived outcomes. Preserve `:enqueue`'s existing HTTP semantics, the same execution ID, the result and the envelope; do not re-file a task for a replay.

**Acceptance:** already-archived successes, errors and timeouts are returned with no NPE. A replay concurrent with the live→settled transition does not produce a second execution. The admission counter does not increase for the replay, and the dispatch happens exactly once.

### A2 — Slot ownership and execution expiry

**Scope:** `ExecutionStore`, `ExecutionRecord`, `ExecutionCompletionHandler`, scheduler, enqueuer and the capacity registry in `workload-metrics`. **Depends on:** A0. This is the most coupled piece of work; keep it in a dedicated PR.

Introduce a lightweight object representing the slot acquired for a specific attempt and a specific function generation. The dispatch callback keeps that reference and can perform an idempotent release even if the store no longer contains the record. Paths that acquire no slot, such as offload, must not release one.

Separate three events: the end of a caller's wait, the end of the local dispatch attempt, and the administrative expiry of the invocation. A single waiter's timeout must not cancel the future shared by other idempotent callers. Administrative expiry concludes the waiters still pending, archives the expected outcome and starts the cancellation/shutdown of the local dispatch. Provide an active expiry, not one that depends solely on the cache's opportunistic activity.

HTTP cancellation is no proof that the backend stopped executing the function: document slots as a limit on local dispatches, without promising an absolute limit on remote work after a disconnect. A strict remote limit would need runtime cooperation, outside the minimal fix. Do not treat the record's eviction alone as proof the dispatch ended.

**Acceptance:** after local attempts conclude or expire, no slots stay held and no futures stay pending; duplicate completions do not drive the count negative. Cover expiry before/after completion, a task that expired while still queued, an old callback during a retry, removal and re-registration of the same name, submission errors and shutdown. Verify that a callback from the old generation does not free a slot of the new one.

### A3 — Retries usable without a queue

**Scope:** completion, the `InvocationEnqueuer` contract and the no-op/async/sync implementations. **Depends on:** A2.

Separate the ability to schedule a retry from the availability of the asynchronous endpoint: `enabled()` must not represent both. Keep retries on the existing queues; in the queueless profile schedule the next attempt with a managed executor and bounded resources, avoiding recursion when the future completes immediately. Exceptions during scheduling must produce an observable terminal outcome.

Keep `maxRetries` and the current retryable-error policy in this PR. Backoff with jitter would need a separate measurement and an explicit definition of the time budget; do not introduce it implicitly alongside the fix.

**Acceptance:** error→success and definitive failure with 0, 1 and 3 retries, in all three queue profiles. With 3 retries there are at most 4 attempts. A full queue, an executor in shutdown and enqueue exceptions terminate the request with no orphaned QUEUED state. The `:enqueue` API stays unavailable where it is not provided.

### A4 — Runtime lifecycle of the sync queue

**Scope:** sync configuration and scheduler, the mutable source, the runtime-config extension. **Depends on:** A0; integrate with A2/A3 before release.

Create the scheduler when the module is loaded, even if queue admission starts disabled. Keep the worker asleep, without continuous polling, when it has no work. The runtime flag decides the path of new invocations; on deactivation, work already admitted keeps draining. Retries of that work follow an explicit policy and are not abandoned.

Publish the related runtime settings through a single immutable snapshot, so that admission and scheduler never observe a partial combination of values during apply/restore. Preserve the runtime-config service's revisions and rollback.

**Acceptance:** boot false→true with a successful dispatch, true→false with a queue and a dispatch in progress, reactivation, a rejected update and rollback. Verify with a real Spring context, not merely by mutating the configuration bean.

### A5 — Idempotency retention and outcome recovery

**Scope:** the key and execution stores, the factory, finalisation, the API and documentation. **Depends on:** A1 and A2.

Bind the key to the execution for its whole life, and start terminal retention at completion. Publishing the key, archiving the outcome and transitioning to the terminal binding must leave no window in which the same key becomes claimable again. Cleanup of pending keys must be tied to the conclusion or the abandonment of the admission.

Separate the deduplication guarantee from payload retention. Proposed decision: if the outcome is evicted for capacity before the end of the window, keep a lightweight tombstone with the execution ID and an expiry. The replay does not re-run the function and returns an explicit "outcome no longer available" error, proposed as HTTP 410. This is a contract change to be implemented with OpenAPI, mapping and tests, not behaviour that already exists.

Bound the number of bindings/tombstones too: with the budget exhausted, refuse new keyed admissions before the dispatch, keeping existing replays servable. The later byte budget must not silently evict the deduplication protection.

**Acceptance:** tests with a controlled clock for a long execution plus terminal TTL, internal retries, eviction for capacity, an exhausted key budget and concurrent requests. A replay inside the window never produces a new dispatch, even when the payload is no longer recoverable. After the documented expiry a new execution is allowed.

### A6 — Application headers across offload

**Scope:** `DefaultOffloadGateway`, the header policy and an HTTP test with two control planes. **Depends on:** A0.

Transfer the allowed application headers as HTTP headers of the second hop too, because the remote control plane rebuilds `InvocationRequest.headers` from the transport. Exclude reserved and hop-by-hop headers and those named by the `Connection` field; preserve the dedicated handling of tracing and offload-hop. The transport's Content-Type must keep describing the JSON envelope.

**Acceptance:** a handler receives the same `x-tenant` locally and offloaded; reserved headers cannot overwrite the gateway's own; tracing and re-offload prevention keep working. Verify the request's reconstruction on the second control plane, not merely the outgoing WebClient's headers.

### A7 — A rate limiter coherent across the window change

**Scope:** `RateLimiter` and its tests; keep the WebFilter already introduced. **Depends on:** A0.

Represent window and count as a single state updated atomically via CAS. Use a steerable time reference to verify the boundaries; avoid a mandatory allocation per request if the state can stay compact. Preserve the windowed semantics and runtime updates of the limit.

**Acceptance:** a controlled interleaving of window change and concurrent requests, with no admission lost from the count. Document that two adjacent windows can still admit a burst: this is not a token bucket. Measure cost per admission/refusal and contention before and after.

## 4. Removing the confirmed bottlenecks

### P1 — A concurrent, bounded container proxy

**Scope:** `RoundRobinFunctionProxy`, the factory, the provider's properties and lifecycle. **Depends on:** A0; integrated verification after A2/A3.

First implementation: a virtual-thread executor with explicit shutdown, non-blocking admission with a cap on the number of invocation requests and a defined refusal once exhausted. Avoid parking an unbounded number of requests on a semaphore. Health must not consume the same permits as invocations. Close exchanges, client and executor on errors and shutdown too.

Propagate a timeout policy coherent with the function to the proxy at provisioning and on updates; remove the fixed 30-second value. Verify the single hop's duration and the caller's overall budget separately. A fully non-blocking proxy stays a later alternative, only if the profile shows a limit of the smaller solution.

**Acceptance:** the test with a backend blocked on a latch must observe several requests in flight before releasing it; with 4 permits and 4 requests the observed maximum must be 4. Test saturation, health, backend change, a timeout longer than 30 seconds with appropriate time/test control, disconnection and shutdown. Repeat the report's benchmark without turning its 284/827 ms into CI thresholds.

### P2 — Sync wake-up on capacity release

**Scope:** `SyncScheduler`, `SyncQueueService`, notifications from capacity/enqueuer. **Depends on:** A2 and A4.

Replace the sleep-based backoff with a notifiable wait on available work or capacity, keeping a safety timeout for expiries and recovery. Notify capacity increases from the governor, registrations and relevant runtime changes too. Use a predicate or a notification sequence to avoid signals lost between the check and the wait.

**Acceptance:** releasing a slot wakes the worker without waiting for the old backoff; no busy loop on an empty queue or at zero capacity; stop interrupts the wait. Preserve progress for non-saturated functions when others occupy the head of the queue. Measure release→dispatch delay, p99 queue wait and idle CPU.

## 5. Metrics and capacity regulation

### M1 — Invocation times separated from attempt times

**Scope:** task/record, completion, metrics, SOJOURN/adaptive readings. **Depends on:** A2 and A3. **Before tuning the controllers.**

Keep an original admission instant that is not replaced on retry; use monotonic time for durations and wall-clock for the instants exposed in the API. Define separately each attempt's wait, the service time and the invocation's total duration. Record exactly one end-to-end conclusion per invocation, errors and timeouts included per the terminal policy; individual waiter timeouts stay a distinct measurement.

Check the consumers before changing existing series. If a metric's meaning changes incompatibly, introduce an explicit series and update SOJOURN, dashboards and queries in the same delivery. Do not treat a duration censored by the timeout as a fast-success sample.

**Acceptance:** the total duration with retries includes every attempt and wait; no double sample on late/duplicate callbacks. Test the controllers' response to a mix of successes, retries and timeouts, not just the timer's value.

### M2 — Scaling aware of replicas already requested

**Scope:** `InternalScaler`, the calculator, the provider and the deployment coordinator. **Depends on:** A0; validate with M1 and the governor.

Read desired and ready from the same `ReplicaStatus`. Preserve the metrics' semantics in the formula; compare the new command with the target already requested, rather than labelling every increase over the ready count alone a scale-up. An intermediate recommendation during startup must not reduce a higher target still in progress. Apply a downscale only with an explicit signal, with cooldown and wake-up protection respected.

**Acceptance:** the case of 10 desired/2 ready/recommendation 4 with no unintended reduction, zero replicas, min/max, a slow or failed startup, rollout, cooldown and concurrent removal. Do not block a real downscale indefinitely because of replicas that never became ready: provide and test handling for the lack of progress.

### M3 — Shared replica snapshot

**Scope:** the deployment coordinator, the autoscaler, the concurrency governor and the provider. **Depends on:** M2.

First solution: a shared snapshot with a timestamp, an explicit TTL and a single refresh request per function/generation. Invalidate after a target change, removal and re-registration. A stale state or a failed GET must not be read as zero replicas. Wake-up and lifecycle keep the ability to obtain a fresh read.

Isolate slow refreshes with bounded concurrency and prevent out-of-order applications. Move to Kubernetes watch/informers only if the polling frequency or the measured latency demands it.

**Acceptance:** fewer duplicate API calls per cycle, freshness within the chosen limit, one slow function does not block the others, and no updates from the old generation. Measure the number of GETs, cycle duration and scaling reaction time.

## 6. Measurement-driven tuning

These tasks first produce a profile and an isolated comparison. If the benefit does not emerge above the baseline's variability, document the result and keep the previous implementation/default.

| ID | Scope and proposed implementation | Dependencies and acceptance |
| --- | --- | --- |
| T1 | HTTP pool: properties for connections, the maximum number of pending acquisitions and the acquisition timeout; a shared provider with an explicit lifecycle. Coordinate the per-destination pool budgets with admission and concurrency, accounting for dispatch and offload traffic. | After A2/A3 and P1. Load on one and on many destinations, a slow backend and an exhausted pool: no unbounded wait and no retry explosion. An improvement in acquisition/p99 without uncontrolled growth of connections and memory. |
| T2 | Queues: profile monitors and scans; keep per-function counters at the sync queue's mutation points. Make the async batch configurable, comparing 2, 4, 8 and 16. Reduce overlapping locks only if the profile proves their cost and the relation between closure, offer and counters stays atomic. | After P2 and M1. Measure throughput, CPU/allocations and p99 per function, including one very active function and many quiet ones. No starvation and no remove/re-register regression. Replacing the global deque is a second step, not a premise. |
| T3 | Memory: add a weighted budget for outcome payloads beyond the count cap, plus an admission budget for live requests. Estimate the weight once, without re-serializing the payload on each access. Include output, headers and overhead; keep headroom for native memory and shared structures. | After A5. Small/large payloads, ASYNC/keyed, a long soak and pressure: retention and deduplication respected, heap/RSS stabilised, the weigher's cost measured. An estimated budget is not a guarantee of an exact heap limit. |
| T4 | Diagnostics: profile meter registration/reads and probe cost on the hot path; reuse the existing metric profiles, and move callbacks and operations not needed for atomicity outside the locks. | After M1. Compare with the same observability in both arms; do not attribute a gain merely to removing metrics that drive the governor or the scaler. |

## 7. Validation matrix

Async queue and sync queue are declared alternatives in the descriptors: test them in distinct builds, do not select them together. Autoscaler and concurrency-control require a queue module.

| Profile | Required checks |
| --- | --- |
| No queue module | Direct invocation, retries and cleanup; `:enqueue` unavailable. |
| Async queue | ASYNC replay, saturation, retries, fairness, function removal. |
| Sync queue + runtime-config | Runtime lifecycle, queue timeout, wake-ups, drain and rollback. |
| One queue + autoscaler + concurrency-control | Scaling with ready different from desired; controller behaviour with corrected metrics. |
| One queue + offload | Headers across two control planes, success/error/timeout, and no release of slots never acquired. |
| Container provider | Concurrent proxy, limits, health, timeout, replica change and teardown. |
| Kubernetes provider | Lifecycle and scaling through the NanoLab scenarios; no provisioning added to the NanoFaaS tests. |
| JVM and native | Build/AOT and smoke of the modified profiles; benchmark the variant actually shipped. |

Starting commands, to be narrowed to the affected tests during each PR:

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none
./gradlew :control-plane:test :control-plane-modules:async-queue:test -PcontrolPlaneModules=async-queue
./gradlew :control-plane:test :control-plane-modules:sync-queue:test :control-plane-modules:runtime-config:test -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane-modules:container-deployment-provider:test -PcontrolPlaneModules=container-deployment-provider
```

For the other profiles add the explicit modules and their `:control-plane-modules:<id>:test` tasks. Run the relevant build and contract/AOT checks before the final integration. Some tests need a container runtime; always record what ran, what was skipped and why. For E2E reuse `deployment-lifecycle-container` and `deployment-lifecycle-k8s` from the NanoLab checkout with `NANOFAAS_ROOT` set. Any extensions to the scenarios belong to that repository and are delivered separately.

## 8. Measurement protocol and promotion criteria

Use the compare workflow and the Prometheus collection already available in NanoLab. Every comparison includes baseline and candidate in the same matrix and on the same infrastructure, at least three repetitions per arm with alternated order. Record warm-up, duration and dispersion; increase the repetitions only when the result stays inconclusive.

Vary one intervention at a time. Cover load below saturation, near the limit and in overload; SYNC and ASYNC where supported; without keys and with replays; small and large payloads; fast functions alongside slow ones; errors/retries. Capacity runs keep the replicas fixed, and a separate matrix then measures autoscaling. Separate short smokes from soaks long enough to cross the configured retention windows.

Decisive metrics: successes within SLO per second, client latency p50/p95/p99, 429s and timeouts, attempts per invocation, queue latency, wake-up delay, HTTP pool wait, occupied slots, CPU per success, allocations per success, heap/RSS, GC and CFS throttling. For ASYNC measure through to completion, do not stop at the 202 response. Keep offered, admitted, refused and concluded separately.

**Proposed initial thresholds:** zero functional regressions admitted; for pure tuning, a target of at least 10% on the target metric and no worsening beyond 5% of useful throughput or p99 in the control scenarios. These are experimental criteria to be compared with the measured noise, not promises of a result nor wall-clock CI thresholds. A correctness fix stays necessary even if it costs CPU: the cost must be reported and optimised without undoing the fix.

## 9. Delivery sequence and closure

| Phase | Tasks | Condition to proceed |
| --- | --- | --- |
| 0 | A0 | Baseline identified, reproductions and index available for the edits. |
| 1 | A1, A2, A3, A4, A5 | Invocations, slots, retries, replays and runtime transitions verified in the affected profiles. |
| 2 | A6, A7, P1, P2 | Offload and rate limiting corrected; the proxy's serialisation and the non-notifiable wait removed. |
| 3 | M1, M2, M3 | Metrics reliable and capacity regulated without confusing target and ready. A new baseline after the fixes. |
| 4 | T1, T2, T3, T4 | Every change backed by a reproducible comparison, or filed as not worthwhile. |
| 5 | Integrated matrix, documentation and release | No slot loss, idempotency duplication or contract regression; operational evidence recorded. |

Every ID is a verifiable unit of work, preferably a small PR; T2/T3 and A2 may need several PRs, each preserving the invariants. The order above does not require parallel agents. Before each PR, re-check the dependencies in the current code, because the first fixes will change the symbols to modify in the later ones.

Update `docs/control-plane.md`, the module READMEs and the metrics/configuration documentation wherever behaviour changes. Change `openapi/core.yaml` or the module fragment for new outcomes/parameters, without editing the generated OpenAPI. Update Helm/Compose for the properties actually introduced and keep their defaults aligned with the application.

Before every commit run `gitnexus_detect_changes`, check the expected symbols and processes and update the direct dependents; after refactors verify the complete scope. After the commit update the index, preserving the existing embeddings.

Keep raw results and summaries in a new experiments directory, without overwriting the overload queue already present. For each optimisation note the previous configuration and the restore procedure. A rollback that restarts the control plane loses the in-memory state: check its operational impact before release and prefer, for tuning, properties that allow values to be restored without a restart where supported.

The plan is complete when every fix has an integrated regression test, every performance suggestion has either a measured implementation or a justified non-adoption outcome, and the final matrix explicitly reports the coverage and limits of the checks.
