**Control-plane and module analysis — 5 September 2026**

Review of the sources in the workspace, Git base `42e49556158bc75a0646fb4cd1044d6c6f6cc454`. No changes to application code. The pre-existing changes to the experiments and to Helm are not part of this review.

Invocation, completion, idempotency, the store, the dispatcher, rate limiting and the main paths of the async-queue, sync-queue, offload, autoscaler, concurrency-control, runtime-config, container-deployment-provider, k8s-deployment-provider and build-metadata modules were examined. This is a targeted review of the critical paths, not a complete certification of every class.

GitNexus MCP was unavailable. The installed CLI, once run, returned `Repository not indexed` and `No indexed repositories found`, contrary to the catalogue reported in AGENTS.md. The references and dependencies below come from reading the sources; they do not represent a graph impact analysis. No symbols were modified and no commits were created.

**Priority problems**

1. **P1 — An asynchronous replay of an archived execution raises a NullPointerException.**

   In [InvocationService.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java), lines 127–135, `invokeAsync` always uses `lookup.executionRecord()`. The factory instead returns `executionRecord=null` when it finds a `settledOutcome`. `terminalResponse(record)` immediately calls `record.snapshot()`. A second `:enqueue` with the same key, after completion and before expiry, can therefore answer with a server error instead of the replay.

   **Fix:** handle `settledOutcome` before touching the record, as the synchronous coordinator already does. Verify the ASYNC replay of successes, errors and timeouts after `settle`, preserving execution ID and envelope. **Evidence:** the archived lookup and the dereference the service performs were reproduced; no end-to-end HTTP request was made.

2. **P1 — The container-local proxy serialises invocations.**

   [RoundRobinFunctionProxy.java](../platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/RoundRobinFunctionProxy.java), lines 33–41 and 80: the server starts with no explicit executor and its handler calls `httpClient.send` in blocking fashion. Round robin selects different backends but does not ensure concurrent dispatches.

   **Experimental evidence:** four simultaneous requests, a backend with a virtual-thread executor and 200 ms of work per request: direct access, maximum concurrency 4 and 284 ms in total; through the proxy, maximum concurrency 1 and 827 ms in total. This is a local proof of the bottleneck, not an estimate of the platform's improvement in production.

   **Fix:** configure a concurrent executor with explicit lifecycle management and a cap on in-flight requests, or use a non-blocking proxy. Also align the fixed 30-second timeout with the function's policy: today a function with a longer timeout can fail early on this hop. Verify parallelism, backpressure, health during a slow request, and resource shutdown.

3. **P1 — The expiry of a record in dispatch can permanently lose a slot.**

   [ExecutionStore.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java), line 78, applies `expireAfterWrite(maxLifetime)` to live records with no finalisation path. [ExecutionCompletionHandler.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java), lines 218–224, ignores a completion if the record no longer exists. The acquired slot therefore stays taken and the future is never completed. It takes a dispatch that outlives `maxLifetime`, for example with long configured timeouts or a store expiry that is too short.

   **Evidence:** with a controlled ticker, a completion after expiry: zero slot releases and a future still pending.

   **Fix:** make slot ownership independent of the record's presence in the cache, with a release exactly once per attempt. The expiry must finalise the waiters and deal with the underlying dispatch; simply freeing the slot while the backend keeps working can violate the real concurrency limit. Verify expiry, late completion and duplicate callbacks together.

4. **P1 — A retry without queue modules leaves the execution pending.**

   [ExecutionCompletionHandler.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java), lines 319–347, puts the record back to QUEUED and always attempts `enqueuer.enqueue`, catching only `QueueFullException`. [NoOpInvocationEnqueuer.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/NoOpInvocationEnqueuer.java) throws `UnsupportedOperationException`. This case is reachable in direct dispatch without queues, with an EXTERNAL/DEPLOYMENT error and retries available. The exception in the `whenComplete` callback ends up in the derived future, which nobody observes.

   **Evidence:** a failed completion with no enqueuer present: an exception, state QUEUED and a pending future. The synchronous client can wait until its timeout instead of receiving a coherent conclusion.

   **Fix:** introduce an explicit retry capability, separate from `enabled()`, which also represents the availability of the asynchronous API. Support scheduled direct retries, or terminate with a defined error if unsupported. Handle every scheduling exception while guaranteeing the record concludes.

5. **P1 — Runtime activation of the sync queue with no scheduler.**

   [SyncQueueConfiguration.java](../platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueConfiguration.java), line 83, creates the scheduler only if `sync-queue.enabled=true` at boot. The runtime extension nevertheless accepts changes to `enabled`, and [MutableSyncQueueConfigSource.java](../platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/MutableSyncQueueConfigSource.java) updates the flag without creating or starting a scheduler.

   **Scenario:** boot with the queue disabled, a runtime update to true, new invocations queued with no consumer. **Fix:** always create the scheduler when the module is loaded and govern its behaviour coherently with the runtime state, or make the flag non-hot-editable. Verify both transitions with in-flight requests. **Evidence:** the path was verified statically; the Spring context was not started for this check.

6. **P1 — The idempotency key can expire before the outcome.**

   [IdempotencyStore.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyStore.java), lines 50–71: a lifetime equal to the maximum of TTL and maxLifetime, with a two-minute floor, starting from the key's publication. The outcome's TTL instead starts at completion. The two windows do not coincide.

   **Evidence:** outcome TTL 5 minutes, maxLifetime 2 minutes, completion after 1 minute. At second 301 the outcome still exists, but `acquireOrGet` returns CLAIMED: the same key can start a second execution.

   **Fix:** keep the binding for the duration of the execution and start the idempotency retention at completion. A conservative lifetime must also cover the time elapsed before completion, not merely the maximum of the two durations. Consider eviction by `maxOutcomes` alongside it: today even an outcome evicted for capacity can cause a new execution, through `claimIfMatches`. If the deduplication window must be guaranteed, keep a tombstone or refuse new admissions when there is no room, instead of silently forgetting the key.

7. **P2 — Offload loses the caller's application headers.**

   [DefaultOffloadGateway.java](../platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGateway.java), lines 92–104, forwards the application headers in the body of `InvocationRequest`, but transfers only the hop and tracing ones as HTTP headers. The remote control plane, in [InvocationController.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java), lines 59–70, always replaces `request.headers` with the HTTP headers it received.

   **Scenario:** a handler reads `x-tenant` or another application header; the value present on the local call disappears when the same function is offloaded. **Fix:** forward an explicit selection of application headers over the remote transport, excluding the reserved ones and those specific to a single hop. Verify a real traversal of two control planes; mocking the remote response alone does not reveal the loss. **Evidence:** static.

**Optimisations and further defects that affect performance**

| Priority | Action | Rationale and verification |
| --- | --- | --- |
| High | Wake SyncScheduler when a slot is released | `SyncScheduler` falls back to `Thread.sleep(50)` under saturation. `SyncQueueService.onDispatchSlotReleased` does cleanup without waking it. A slot that has just been freed can stay unused until the next wake-up. Use notifications with a predicate check and a safety timeout; measure p99 of the wait and slot utilisation. |
| High | Keep the original admission instant across retries | `handleRetry` builds the task with a fresh `Instant.now()`; the completion computes e2e and queue wait from the last attempt's task. The e2e metric therefore underestimates the total time and feeds SOJOURN a partial signal. Separate attempt duration from invocation duration, timeout outcomes included. |
| High | Distinguish desired and ready replicas in the autoscaler | `InternalScaler` uses only `getReadyReplicas` to decide and then overwrites the desired number. With 10 desired, 2 ready and a ratio of 2 it can order 4 and call it a scale-up, actually reducing the target of 10. Account for scaling already in progress and check slow rollouts/startups. |
| Medium | Share a replica snapshot between the autoscaler and the governor | The Kubernetes provider issues a GET for `getReplicaStatus`; both loops ask for the per-function state. Consider a shared cache/watch with freshness handling, and slow reconciliations isolated per function. Measure API requests and cycle duration. |
| Medium | Reduce locks, scans and diagnostics on the queue path | The sync queue has a global deque with scans; the async queue combines the `FunctionQueueState` monitor with the `ArrayBlockingQueue` lock. Try per-function counters and a configurable async batch, today fixed at 2. Optimise only after a CPU/allocation profile, preserving fairness and function removal. |
| Medium | Configure the HTTP pool limits explicitly | `HttpClientConfig` configures timeouts and body size, but not an explicit budget for connections and pool waits. Align that budget with the admitted concurrency, so as not to create a second, barely visible queue. Measure acquisition waits before enlarging the pool. |
| Medium | Bound memory in bytes, beyond the number of outcomes | `maximumSize` bounds the number of outcomes, not the weight of the output and headers of ASYNC or keyed executions. Estimating from the compact outcome alone does not describe those payloads. Consider weighted budgets and global admission, without weakening deduplication. |

A further concurrency defect is in `RateLimiter.allow`: the window change and the counter reset are two distinct operations. One thread can publish the new second, others increment the counter, and the first zero those increments too. Use a coherent atomic state for window and count; verify the second boundary with controlled scheduling. Replacing the counter with a LongAdder is not enough for a strict limit.

No priority defects emerged on the build-metadata read path that was examined. For runtime-config the concrete problem identified is the interaction with the sync queue's lifecycle.

**Validation and order of work**

The sources involved in the four state checks were recompiled with Java 25, using the dependencies already in cache and local classes for the collaborators. The proxy check used its current source and sockets on localhost. Harness and output are in `/tmp/nanofaas-audit-0905/` (`Audit.java`, `Audit.out`, `ProxyAudit.java`, `ProxyAudit.out`). The full Gradle suite was not run, nor a Kubernetes/container E2E of the platform. These temporary checks are not regression tests integrated into the repository.

Suggested order: fix the replay, the slot/retry lifecycle and idempotency; remove the proxy's serialisation and the sync wake-up delay; make metrics and scaling reliable; finally work on the pool, batching and allocations.

To compare performance, use successful-invocation throughput, end-to-end p50/p95/p99, 429s, timeouts, attempts per invocation, slot utilisation, CPU per success, bytes allocated per success and GC pauses. Compare at equal CPU, memory, payload, backend and offered load; include traffic with idempotency keys, retries, and slow functions concurrent with fast ones. Increasing queue depth on its own can worsen latency without increasing useful throughput.
