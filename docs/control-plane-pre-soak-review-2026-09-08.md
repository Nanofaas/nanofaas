**Pre-soak review: correctness, memory and module boundaries — 8 September 2026**

Reviewed revision: `1d9e2f5518c21641be2e791cca9a952ca4128d34`, `main`, version 0.21.0.

The platform has useful module boundaries and several sound performance decisions,
but execution ownership is still split across too many collaborators. There are
reproducible correctness defects and memory-retention paths worth fixing before
using a soak to judge the release. Moving classes between JARs alone would leave
these defects intact. The first architectural change should establish one owner
for an invocation's terminal transition and for each dispatch attempt's resources.

This review does **not** establish that the RAM increase suspected in
[issue #207](https://github.com/miciav/nanofaas/issues/207) was caused by any
particular finding below. Several findings require traffic absent from the
original experiment: idempotent waiters, offload, dynamic removal, or a different
queue profile. Attribution needs the actual workload and memory measurements.

**Scope and evidence**

The review follows invocation/admission, dispatch/completion, retries, caches,
both queues, capacity accounting, deployment lifecycle, replica observations,
autoscaling, runtime configuration, HTTP resources and module wiring. SDK runtime
and callback paths received a narrower inspection; CLI, example business logic,
archived experiments and every SDK method were not exhaustively audited.

Nine diagnostic reproductions are preserved in
[the evidence directory](experiments/pre-soak-review-2026-09-08/README.md), with
[source](experiments/pre-soak-review-2026-09-08/Audit.java) and
[recorded output](experiments/pre-soak-review-2026-09-08/results.txt).
They recompile **133 current production source files**, run on Java 25.0.4 with
a 256 MiB heap, and use controlled dispatch futures, listeners and adapter
failures. They do not start Docker, Kubernetes or an HTTP application. All nine
observed the behavior described below. Their assertions intentionally recognize
the defective behavior; they are evidence, not a passing product test suite.

No application code, configuration defaults or existing tests were changed.
The full Gradle suite, AOT/native build, HTTP E2E and soak were not run. The
pre-existing changes to the overload experiment's state/output files were left
in place.

GitNexus CLI 1.6.11 was usable through the registered `nanofaas` index in the
other worktree. Its indexed commit matches this review. Queries and context
were cross-checked against the current sources; historical source snapshots in
the index make unqualified symbol names ambiguous. Architectural counts below
come from production source imports, not from treating graph communities as
Gradle modules.

The final `gitnexus detect-changes --scope all` completed and reported no changes
in that indexed worktree. Because this review's new documents/harness live in
the primary worktree, their scope was checked separately with Git there:
no diff under `platform`, `sdks`, `openapi` or `deploy`; only the new review
artifacts and the two pre-existing experiment-file changes. The new untracked
audit files are not covered by the existing graph.

**Findings that should drive the next changes**

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| R1 | High | The outcome weigher can ignore almost the entire retained payload | 30 MiB of distinct payloads retained with an 11,600-byte estimated budget |
| R2 | High | Archiving has a deduplication gap under outcome eviction | Replay lookup becomes a new execution before the key's terminal notification |
| R3 | High | A short waiter changes the shared outcome and makes later replay inconsistent | Long waiter receives success; replay of that same execution receives timeout |
| R4 | High | Offload completion after a waiter timeout can leave a finished call in the live store | Backend finished; live record remains and shared future is pending |
| R5 | High | Disabling the sync queue lets direct completion release an unowned slot | One old dispatch remains active while its recorded slot count falls from 1 to 0 |
| R6 | High | Failed container removal loses proxy ownership and provider state | Adapter throws; proxy is not closed and provider tracks zero states |
| R7 | Medium | The idempotency-key capacity check is not atomic with claiming | `maxKeys=1` admits 16 simultaneous distinct claims |
| R8 | Medium | Function-name history accumulates after removal | 1,000 removed metric names and 1,000 invalidated replica entries remain |

**R1 — The byte budget is not conservative for structured payloads.**

[OutcomeWeigher][weigher], particularly lines 61–100, returns zero beyond depth
4 and stops walking a collection/map after 256 elements. The remaining objects
are still strongly referenced by the outcome. A bounded traversal is a useful
cost constraint; assigning no cost to everything skipped makes the estimate
arbitrarily smaller than the payload it claims to bound.

The reproduction retains 30 different 1 MiB Latin-1 strings, each wrapped in five
lists. Every outcome weighs 176 according to the production weigher; all 30 fit
inside `maxOutcomeBytes=11600`. The stored payload lengths total 31,457,280
bytes. This is a count of actual retained payload bytes, **not a heap/RSS
measurement**. A separate wide-list case places a 1 MiB string after 256 nulls
and receives a weight of only 112. These are ordinary JSON-compatible shapes.
The external decoder permits responses up to 16 MiB by default, so the problem
does not require an unsupported object type or an unlimited HTTP response.

The original string-only benchmark remains valid for that payload shape. Its
conclusion cannot be extended to arbitrary maps/lists. There are smaller
underestimates too: non-Latin-1 strings, map-entry overhead, cache keys/nodes,
and objects assigned the constant opaque weight.

Change the fallback at a traversal limit: either retain a conservative size
computed while decoding, use a representation with a known retained size, or
decline to cache an outcome whose size cannot be bounded. Preserve its
deduplication tombstone. A conservative estimate can reject some large outcomes;
the alternative must not silently price their large subtrees at zero. Test deep,
wide, Unicode, error, header and opaque payloads as well as plain strings.

Also clarify configuration: `ExecutionStore` now calls `maximumWeight`, not
`maximumSize`. `max-outcomes` derives a default byte budget; it is not an
independent count cap when `max-outcome-bytes` is explicit. The comments/table
still describing two independently enforced ceilings should be corrected.

**R2 — Outcome eviction can reopen the key during the terminal transition.**

[ExecutionStore.settle][store] performs these operations at lines 209–215:
publish the outcome, invalidate the live record, then notify terminal listeners.
The factory's listener eventually calls `IdempotencyStore.markTerminal`.

Between live-record invalidation and that notification, an oversized outcome
can already be evicted. The key is still `published`, so
[InvocationExecutionFactory][factory] finds neither a live record nor an
outcome and enters `claimIfMatches`. That method permits reclaiming a published
binding. The result is a new execution ID for a function that already ran.

The reproduction pauses a listener at this exact boundary, with the outcome
over the configured capacity, then performs a real factory lookup. It obtains
`isNew=true` and a different ID. It demonstrates authorization of another
dispatch, not a measured frequency of duplicate HTTP executions. The pause
models a valid scheduling interleaving; it does not alter cache operations.

The earlier fix for “dispatch completes before admission publishes the key”
addresses another ordering. It does not close this one. Make the binding
non-reclaimable before the last live reference can disappear, including when
payload retention rejects the outcome. Commit this invariant as part of the
terminal transition; metrics and other best-effort listeners should observe it
afterwards. Cover both admission/publication orderings and concurrent eviction.
Do not fix it by unconditionally lengthening a TTL.

**R3 — Waiter timeout and invocation outcome have conflicting semantics.**

[ReactiveInvocationCoordinator][reactive], line 105, calls `markTimeout()` on
the shared execution when one subscriber's wait expires. `suppressCancel=true`
protects the shared future, but it does not protect the shared state.

In the reproduction, a long waiter owns an execution with a key. A second
waiter with that key uses a 1 ms budget and times out. The backend then returns
success. [ExecutionCompletionHandler][completion], lines 260–286, delivers
that real success to the existing long waiter while deliberately preserving
the record's TIMEOUT state. After settling, a replay reads TIMEOUT with no
output. A waiter arriving after the short timeout can receive TIMEOUT immediately
even though its own budget has not elapsed and the dispatch is still running.

The stable TIMEOUT state is explicitly documented in OpenAPI and asserted by
existing tests. This is therefore a **contract inconsistency requiring a
deliberate API decision**, not a claim that `markTimeout` alone violates the
current state-transition documentation. Returning a real success to one caller
while permanently forgetting that answer for its idempotent replay is the
problem.

Prefer a waiter timeout that only concludes that waiter's response. An
invocation deadline or administrative cancellation may conclude the shared
execution. Keep those clocks and statuses separate, and update OpenAPI along
with the implementation. The alternative is one authoritative invocation
timeout delivered consistently to every waiter, with the loss of independent
waiter budgets made explicit. In either case, preserve one replayable terminal
outcome and record waiter timeouts separately from invocation conclusions.

**R4 — The same timeout can retain a completed offload for 30 minutes.**

Both `completeOffloadedExecution` and `failOffloadedExecution` in
[ExecutionCompletionHandler][completion], lines 90–141, return immediately
when the record is terminal. They do not complete its shared future or settle
it in that branch.

A long offloaded request and a shorter idempotent waiter make this reachable
without relying on the gateway's 50 ms timeout margin failing: the second
waiter marks the shared record TIMEOUT before the owner's remote call ends.
The reproduction then completes the remote sink successfully and observes
`live=1`, `sharedFutureDone=false`, `archived=null`. The original caller is
still waiting despite the remote result having arrived.

The request/task and its payload remain referenced by `inFlight` until
administrative expiration, **30 minutes by default**. Offload does not acquire
local dispatch slots, so slot capacity is not a bound on this population.
At a sustained affected admission rate, retention can approach
`affected requests/second × 1800 seconds × retained bytes/request` before it
levels off. This is a defect even when the heap eventually plateaus.

Make finalization unconditional for the local attempt's completion while
applying the chosen authoritative-outcome policy separately. Cover both remote
success and remote failure after a shorter waiter's timeout. Inspect queue-side
terminal early returns under the same invariant; `dispatch` also returns on an
already terminal queued record without settling it.

**R5 — Sync-queue disablement breaks slot ownership.**

When the sync gateway is disabled, `admitLocally` in
[ReactiveInvocationCoordinator][reactive] falls through to direct dispatch.
The sync module's [enqueuer][sync-enqueuer] still returns `enabled=false`
because it does not expose the asynchronous enqueue API. This direct path does
not acquire a slot. Completion nevertheless calls `releaseDispatchSlotOnce`,
which releases by function name through that same sync enqueuer.

The reproduction reserves the one slot of a pre-existing queued dispatch,
models the disabled gateway, and runs one new direct invocation to completion.
The old dispatch is still running; capacity reports zero in flight. A draining
or subsequently re-enabled scheduler can now admit more work against a false
capacity count. The per-record “released attempts” set prevents a duplicate
release by this record; it cannot prove the record ever acquired a slot.

Introduce an attempt-owned lease obtained at actual acquisition. Release that
lease, not a name-based counter. Direct, queued, retry and offload paths should
explicitly carry either the lease they own or no lease. Define how admission
behaves while a queue drains after disablement. Test transitions with old work
in flight, rather than only toggling a bean or dispatching after enablement.

**R6 — A deprovision exception leaks an untracked proxy.**

[ContainerLocalDeploymentProvider][container], lines 175–191, removes its
`FunctionState` before deleting replicas and closes the proxy only after all
deletions succeed. If `adapter.removeContainer` throws, the state is already
gone and `safeClose(state.proxy)` is skipped. The reproduction injects that
adapter error and observes `proxyClosed=false`, `trackedStates=0`.

A real proxy owns a running HttpServer, an executor and an HttpClient. Dropping
the provider's map entry is not equivalent to closing those resources. A later
deprovision enters the “state absent” branch and has no proxy reference left
to close. At the service layer, failed deprovision leaves `deprovisioned=false`,
so rollback restores registry/listeners without reconciling the lost provider
state. Operational state can diverge as well as retaining resources.

Keep ownership until cleanup is complete, attempt independent cleanups despite
one failure, and preserve enough state for retry/reconciliation. Ensure local
proxy cleanup also has a context-shutdown owner. Context shutdown should close
local proxy/client resources without automatically deleting persistent managed
containers intended for recovery. Test partial replica failure, a second
deprovision attempt and Spring-context shutdown.

**R7 — `maxKeys` is a check, not an atomic admission reservation.**

[IdempotencyStore][keys], lines 129–134, checks `size() >= maxKeys` and then
inserts into a concurrent map. Different keys can all observe spare capacity
before any insertion. A barrier after the real size read produces 16 accepted
claims with a configured budget of one.

This establishes concurrent overshoot, not unlimited growth from a single
sequential stream. Reserve capacity atomically before a new claim and return
it on losing a claim race, abandonment and expiration. Preserve existing replay
availability at capacity. The capacity account must include pending bindings,
and expiry/removal must not return a permit twice. The unconditional Caffeine
`cleanUp()` inside each new-key size check also merits profiling under keyed
load; this review does not assign a measured cost to it.

**R8 — Deletion leaves historical-name state indefinitely.**

[Metrics][metrics] retains removed function names in `removedFunctions` to
prevent late callbacks recreating meters. The set is reduced only if the exact
same name is registered again. The sync queue and its metrics maintain similar
removed-name sets. [ReplicaStatusSnapshot][replicas], lines 149–160, clears
an entry's contents but never removes the entry itself.

The reproduction registers/removes 1,000 different metric names and reads/
invalidates 1,000 replica targets. Both historical collections still contain
1,000 entries. These are small entries, but growth follows all names ever seen,
not the current function count. Reusing one name throughout a soak will miss it.
The offload module additionally registers function-tagged counters directly in
the meter registry without a corresponding removal listener.

Use a function-generation identity and a lifecycle that retires state once its
users drain. A plain deletion of tombstone sets can reintroduce stale callbacks
and meters, while a blind TTL may forget a still-running generation. Replica
entries can be removed with generation/entry identity checks so an old refresh
cannot repopulate the replacement. Verify registry meter count as well as maps.

**Additional memory and operational risks**

These are distinct from the reproduced correctness defects above. They should
be treated as missing bounds or hypotheses until measured under the relevant
workload.

| Area | Evidence in current code | Consequence / next check |
|---|---|---|
| Direct core admission | No queue module means the initial attempt bypasses slot acquisition. The diagnostic harness observes 100 simultaneously dispatched live records with `concurrency=1`. | Missing admission bound, not a claim that optional governor support exists in core-only mode. Bound accepted work independently of whether a queue/governor is installed. |
| Live/queued payload bytes | `inFlight` has lifetime expiration but no count/byte admission cap; async queue depth is per function; sync depth is a global count. | Time and request count do not bound aggregate bytes across functions/payload sizes. Include parsed input, queued requests, pending HTTP acquisition and waiters. |
| Replica refresh pool | `ReplicaStatusSnapshot` uses a static `Executors.newFixedThreadPool(2)`. This factory uses an unbounded task queue. Invalidation clears `inFlight` without cancelling the submitted task. | Threads are bounded; queued refresh jobs are not. Slow GETs plus many functions or repeated invalidation can accumulate tasks. Use a managed executor with a bounded queue, reject/coalesce refreshes, and export queue/age metrics. [JDK contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html). |
| Replica refresh stalls | A cold or too-stale `read` fetches synchronously or joins an existing future with no local deadline. Scaler/governor loops visit functions sequentially. | One slow provider can delay other functions after invalidation or the stale bound. Provider-level timeouts help but do not isolate loop latency. Serve an explicit unavailable observation and schedule refresh separately. |
| Wake-up scheduling | `DeploymentWakeUpGate.wake` schedules a timeout for every new wake check and never cancels that timer after early success. Eligible warm functions still take the fresh-read path. | Completed timeout tasks remain queued until their deadline; transient population scales with check rate and configured wake-up timeout. Measure scheduled-task depth and provider GET rate for minReplicas=0, already-ready functions. |
| Dispatch cancellation | Administrative expiry finalizes bookkeeping but retains no handle to cancel the actual dispatch future/subscription. | A function timeout longer than maxLifetime can leave HTTP work and its payload alive after local admission capacity is released. A lease should include local cancellation ownership; remote execution still needs cooperative cancellation if a strict remote bound is required. |
| HTTP destinations | `HttpClientConfig` sets 500 connections and derived pending count per destination; it does not configure idle lifetime, background eviction or inactive-pool disposal. | Endpoint churn and many destinations need an aggregate resource policy. Disposal at context shutdown does not remove historic destinations during a long-running process. [Reactor Netty contract](https://projectreactor.io/docs/netty/release/api/reactor/netty/resources/ConnectionProvider.Builder.html). |
| Container proxy buffering | [RoundRobinFunctionProxy][proxy] reads the full request and buffers the full backend response as byte arrays. Its invocation permit caps requests, not bytes; the backend timeout starts after inbound body reading. | A large backend response can consume memory in the proxy before the control-plane decoder's 16 MiB limit rejects it. Put byte limits/read deadlines at this hop; compare bounded streaming against buffering. |
| WaitEstimator | Keeps one timestamp per dispatch in global and per-function deques; pruning is triggered by activity on those deques. Default window is 30 seconds. | Allocation scales with throughput. Previously active, now idle functions can retain old per-function samples until read/removal. Consider fixed time buckets only after measuring cost and accuracy. |
| Registry persistence | Every registry write copies the full function map and persists a catalog snapshot; replica changes use this path too. | With many functions and frequent scaling, allocation and serialized persistence grow with catalog size. Measure separately from invocation retention before replacing the snapshot design. |
| Function runtime process | Java/Go/JS callback queues and Python callback permits have bounds, but bound payload count does not bound bytes. Timeout of a synchronous Python handler running via `to_thread` does not stop the running handler thread. Java-lite creates an HTTP executor without a stored shutdown owner. | Account for function-container RSS separately from control-plane RSS. Test slow handlers, callback backpressure and runtime shutdown in the SDK scope; these observations do not explain control-plane heap growth by themselves. See Python's [thread execution](https://docs.python.org/3/library/asyncio-task.html#asyncio.to_thread) and [future cancellation](https://docs.python.org/3/library/concurrent.futures.html#concurrent.futures.Future.cancel) contracts. |

The fixed-name, keyless happy path avoids many of these populations. A clean
result for that path would be useful but would not exercise the memory risks
of ASYNC output retention, replay, offload, deletion or endpoint churn.

**Architecture assessment**

The current build has 97 core Java source files, 19 common source files, seven
workload-metrics files and nine optional modules containing 96 source files.
Those are file counts, not a complexity score. The strongest existing decisions
are the separation of live records from compact outcomes, optional backend
providers, module selection constraints, and distinct concurrency/scaling
controllers. The measured rejection of some tuning ideas is also a useful
discipline to preserve.

The build enforces a meaningful direction: core has no source imports of
optional modules; selected modules are added to its runtime. Modules compile
against the **whole control-plane implementation JAR**, however. Async/sync
queue code imports and mutates `ExecutionRecord` and `ExecutionStore`, and
schedulers call `InvocationService` to dispatch. These are access to internal
lifecycle implementation, not narrow extension contracts. The compile graph
is therefore less decoupled than the absence of direct module-to-module
imports suggests.

`InvocationEnqueuer` combines admission, async capability, retry scheduling and
capacity acquisition/release. Its `enabled()` is deliberately false for
implementations whose `enqueue()` works. `workload-metrics` contains mutable
capacity state that determines whether work can run, not just observations.
`ExecutionCompletionHandler` combines dispatch, wake-up, retry, state changes,
slot release and metrics. The queues also conclude executions themselves.
These responsibilities explain R2–R5 better than class sizes alone do.

The architecture tests protect package direction and module isolation, but
cannot express “only the lifecycle owner may conclude an invocation” while
the public mutable types remain available to every module. They should evolve
with the contracts, rather than merely blessing a new package arrangement.

**Concrete boundary changes, in implementation order**

| Current element | Proposed destination/responsibility | Why |
|---|---|---|
| Shared terminal decisions in coordinator, completion handler and queue modules | One `ExecutionLifecycle` component in the core's execution area; queues report events such as expired/removed, rather than mutating records | One authoritative result, one archive/key transition, guaranteed future completion |
| Name-based slot release and released-attempt set | Attempt-scoped `DispatchLease` and `DispatchAttempt` owned by that lifecycle; include function generation and cancellation handle | Prove acquisition before release; clean up old generations and timeout paths |
| `FunctionCapacityRegistry` / `FunctionCapacityState` in workload-metrics | Mandatory execution/capacity implementation owned by core, independent of a queue module | Direct admission also needs bounds; metrics should observe execution state rather than own it |
| `InvocationEnqueuer` | Separate small contracts for admission, retry scheduling and dispatch capacity; explicit async capability | Remove the overloaded `enabled()` semantics and implicit release obligation |
| Module-facing contracts scattered through core | A small mandatory `platform/control-plane-spi` library, after the contracts have stabilized | Queue/provider modules should compile against interfaces and immutable DTOs instead of the application JAR |
| Queue schedulers invoking `InvocationService` | A dispatch port supplied by the core lifecycle | Keep HTTP/service orchestration out of scheduler dependencies |
| `Metrics` dependency from concurrency-control | A read-only invocation-observation contract with documented timer semantics | Governors should consume observations without accessing registration/removal machinery |
| Sync queue's compile-only dependency on runtime-config implementation | Put only extension contracts in the SPI; keep mutable queue settings and their adapter with sync-queue | Preserve optional runtime configuration without coupling to its implementation module |
| `ReplicaStatusSnapshot` and static default executor constructed inside coordinator | A context-owned bean with bounded executor, fresh/stale/unavailable observations and explicit removal | Configuration, lifecycle and queue bounds become testable and observable |
| Wake-up beans installed in core even without managed deployments | A deployment orchestration package/configuration activated for a managed provider | Avoid unused background resources and narrow the invocation dependency to a readiness port |

The SPI should contain only the contracts required by actual consumers: queue
submission/events, dispatch, capacity/observations, function lifecycle views and
managed provider/replica DTOs. Keep `ExecutionRecord`, Caffeine, executors,
Spring controllers and mutable registries out of it. `common` remains the
shared wire/runtime model; it should not absorb control-plane lifecycle state.

Start with package/component boundaries inside the current core. Once modules
use the narrow ports, extracting the SPI is a build change with enforceable
benefits. A separate mandatory execution implementation library may make sense
later if it enables independent lifecycle tests and packaging; it is not needed
just to turn one class into several files. Likewise, a separate
`deployment-management` module is a later option if managed deployments need
to be excluded cleanly from minimal builds. Do not move the entire registry
there: LOCAL and EXTERNAL functions need it too.

Keep async-queue and sync-queue separate: their admission semantics, fairness
and data structures differ. Share attempt ownership and lifecycle contracts,
not an abstraction that forces both queues into the same algorithm. Keep
autoscaler and concurrency-control independently selectable: replica count
and per-replica concurrency remain different controls. They should share fresh
observations and generation identity, not necessarily a scheduling thread or
a merged module. Container and Kubernetes providers remain independent
adapters. Offload's small size is not a reason to merge it into core; its
transport is optional, while its local completion must use the shared lifecycle.
Build-metadata and runtime-config remain useful feature modules.

**Impact and migration constraints**

GitNexus upstream analysis, disambiguated to current production files:

| Symbol | Direct dependencies | Reached symbols | Reported risk | Process coverage reported by the graph |
|---|---:|---:|---|---|
| `ExecutionStore` | 8 | 26 | **HIGH** | Three process groups, including sync invocation and claimed-record creation |
| `FunctionCapacityRegistry` | 17 | 30 | **HIGH** | One grouped entry point; interface dispatch is explicitly a lower bound |
| `InvocationEnqueuer` | 6 | 8 | MEDIUM | No grouped processes resolved |
| `ExecutionCompletionHandler` | 4 | 8 | LOW | No grouped processes resolved |

The HIGH findings are a migration warning. Source inspection shows wider
semantic reach than some process counts suggest: queue modules, synchronous
waiters, offload, retries, metrics and lifecycle listeners must be checked
together. A LOW class-level graph result is not a claim that changing its
terminal policy is low-risk. Run method-specific impact/context before actual
edits, and use GitNexus rename previews for any symbol rename.

Migrate behind existing adapters, then forbid module imports of concrete
execution/store/service implementations with ArchUnit. Contract tests should
cover no queue, async queue, sync enabled/disabled, offload, retries and
remove/re-register. The critical assertions are results and resource ownership,
not merely bean presence or method calls.

**How this changes the soak proposal**

Issue #207 proposes approximately 90 minutes per revision, observing retained
heap, RSS and the execution/key caches. Keep that steady workload comparison,
using its actual baseline `e35405ee` and the selected candidate on the same
machine, runtime flags, resource limits, payload corpus and offered rate.
Do not silently substitute the v0.20.0 throughput comparison as its baseline.

Add short targeted cases before committing hours of machine time:

| Workload | What it distinguishes |
|---|---|
| Fixed functions, unkeyed SYNC, moderate constant rate | Original RAM question with little readable-output retention |
| ASYNC/keyed output with flat, deep and wide shapes | Actual payload-budget behavior and replay/eviction correctness |
| Same key, different waiter deadlines, direct and offloaded execution | Shared-outcome consistency and completed work left in `inFlight` |
| Errors/retries and queue disable/enable while work is active | Attempt ownership, orphaned state and misleading slot counts |
| Register/invoke/remove distinct names and change endpoints | Historical maps, meters, proxy resources and HTTP-pool lifetime |
| Traffic stop and drain after a steady segment | References that remain after the work and relevant retention windows end |

The long baseline comparison and these targeted workloads answer different
questions; do not combine them into one opaque average. Keep successful work
and offered work comparable, and track admitted/refused/completed/replayed
counts so increased useful throughput is not mistaken for a memory regression
at a different effective workload.

Collect retained heap at controlled post-GC checkpoints, GC logs, process RSS,
cgroup memory composition, direct-buffer usage, thread count and open sockets.
Verify that requested collections actually occurred, and separate their
latency effects from throughput/SLO reporting. Record per-runtime/container
memory so function processes and the container proxy are attributed correctly.
Add pending dispatch/acquisition counts, replica-refresh queue depth, timer
queue depth, current/historical function state and total registered meters;
the three existing cache gauges cannot see these populations. For outcomes,
record configured and estimated weight as well as count, while remembering
that R1 makes the current estimate unreliable for some shapes.

A growing post-GC population needs object histograms or a heap dump to identify
retainers. A **plateau can still be a bug**: R4 can level off at the 30-minute
expiration boundary while retaining completed invocations throughout. Therefore
the issue's suggested “higher plateau → change chart defaults rather than code”
is not sufficient as a decision rule. First determine which objects remain,
why they remain, and whether they represent useful retained state.

Suggested work sequence: preserve this revision/evidence, fix R1–R6 in small
reviewable changes, make key capacity and deletion cleanup reliable, run the
targeted profiles, then execute the controlled soak comparison. If the immediate
purpose is to explain the historical RAM result before fixes, run the unchanged
audited candidate as a separate forensic arm and label it explicitly. Broader
module extraction should follow stable lifecycle contracts and a measurable
baseline, so structural moves do not obscure attribution.

[weigher]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/OutcomeWeigher.java
[store]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java
[keys]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyStore.java
[factory]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactory.java
[reactive]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinator.java
[completion]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java
[metrics]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/Metrics.java
[replicas]: ../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ReplicaStatusSnapshot.java
[sync-enqueuer]: ../platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueInvocationEnqueuer.java
[container]: ../platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProvider.java
[proxy]: ../platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/RoundRobinFunctionProxy.java
