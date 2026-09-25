# ADR 0001 — Execution binding lifecycle contract

- **Status:** Accepted (normative contract for the control-plane lifecycle campaign)
- **Date:** 2026-09-08
- **Campaign:** control-plane lifecycle, memory and modularity
- **Applies to:** `platform/control-plane` core + optional modules (`async-queue`, `sync-queue`, `offload`, `container-deployment-provider`, …)
- **Reviewed against:** `61d72e73` (P00, branch `control-plane-lifecycle-memory`); analyzed revision `1d9e2f55` (`main`, v0.21.0)
- **Amended:** P20a at `92aae4e9` — §3.3 and §4 updated to what P05/P06 delivered, §8.1 (ownership by
  scope), §8.2 (generation identity and the active/retiring/closed protocol) and §10 (residual
  inventory after P06) added
- **Related:** [pre-soak review](../control-plane-pre-soak-review-2026-09-08.md) findings R1–R8; [campaign plan](../plans/2026-09-08-control-plane-lifecycle-memory-and-modularity.md) §2 (invariants I1–I10), §4 (P01–P09)

## 1. Context and problem

An invocation's life is owned by too many collaborators at once: the reactive coordinator
(`ReactiveInvocationCoordinator`), the completion handler (`ExecutionCompletionHandler`), both
queue modules, the offload gateway, and the two stores (`ExecutionStore`, `IdempotencyStore`) all
mutate the same mutable `ExecutionRecord`. The pre-soak review reproduced concrete defects from
that split ownership:

- a short waiter stamps the **shared** record `TIMEOUT`, so a later replay of the same execution
  forgets the real answer (R3);
- an eviction window between live-record invalidation and the key's terminal notification lets a
  published key be re-claimed (R2);
- offload completion after a waiter timeout leaves a finished call in the live store (R4);
- sync-queue disablement lets direct completion release a slot nobody acquired (R5);
- `maxKeys` is a check, not an atomic reservation (R7).

The campaign therefore needs a single, written contract for the binding lifecycle — one that P02–P09
implement against. This ADR is that contract. It records the invariants, the complete state/event
transition table, the public key scope, and a per-resource ownership matrix. As first written it
changed no production code; it is normative for the code changes that follow. §8.2 and §10 were
added later, by P20a, alongside the small core contracts they describe.

It is deliberately explicit about **one API decision** (R3) that changes a behavior currently
documented in `openapi/core.yaml`. See §5.

## 2. Decision summary

1. **One execution, one shared terminal result.** The shared `CompletableFuture`, the archived
   `Outcome`, and every replay/poll of the same execution always agree on the terminal result.
   Only the transition table's owner may conclude the execution.
2. **Per-waiter timeouts (the R3 decision).** A waiter's own budget concludes only that waiter's
   wait. It never mutates the shared record, key, store, lease or counters. The shared execution
   keeps running, and its real result is what replay/polling observe. See §5.
3. **Attempt-scoped leases.** Every dispatch attempt acquires exactly the resources it releases;
   the lease is owned by the attempt (and the function generation), not by the function name alone.
4. **Internal generation identity.** A function's generation is an internal identity used to retire
   state and to fence late callbacks/refreshes. It is never part of the public key scope, and it
   cannot bypass a still-valid tombstone by reusing the name. See §7.
5. **The transition table (§6) is exhaustive and self-consistent.** Every enumerated event has a
   defined initial and next state, an explicit owner, and a shared result that is identical across
   future, archive and replay. Where the current code deviates, the deviation is named and mapped to
   the task that closes it (§9).

## 3. Terminology

### 3.1 Execution states

`ExecutionState` (enum, `it.unimib.datai.nanofaas.controlplane.execution`):

| State | Meaning | Terminal? |
|---|---|---|
| `QUEUED` | admitted; waiting in a queue or about to be dispatched | no |
| `RUNNING` | a dispatch attempt is in flight | no |
| `SUCCESS` | the invocation succeeded | yes |
| `ERROR` | the invocation failed (backend error, removal, expiry, offload failure, retry exhausted/refused) | yes |
| `TIMEOUT` | the shared execution timed out (an execution-level deadline, not a single waiter's budget) | yes |

Transitions between non-terminal states are allowed in either direction in the current
implementation (`ExecutionRecord.canTransition`), because a retry resets `RUNNING -> QUEUED`
(`resetForRetry`). Terminal states are final: no transition leaves them. Terminal markers
(`markSuccess`, `markError`, `markTimeout`) are no-ops on an already-terminal record.

### 3.2 Idempotency-key states

`IdempotencyStore.StoredKey` (internal, per `functionName + ":" + key`):

| State | Meaning | Who may transition out |
|---|---|---|
| `pending` | a claim token is held; admission not yet published | `publishClaim`, `abandonClaim` |
| `published` | bound to a live execution id | `markTerminal`; `claimIfMatches` may re-claim only if the execution vanished without concluding |
| `terminal` | the execution archived; this is the tombstone | nobody (expires by `ttl`) |

### 3.3 Identities

- **Execution id** — the public identifier of one execution (UUIDv4-shaped, `X-Execution-Id`).
- **Idempotency key** — the public, caller-supplied `Idempotency-Key` header, scoped per function
  name (see §7). Not per caller; there is no tenant identity.
- **Claim token** — internal, unguessable, transient handle for a `pending` key binding.
- **Generation identity** — internal identity for one incarnation of a function name:
  `FunctionGeneration` (`functionName` + a monotonic id), minted by
  `FunctionCapacityRegistry` on registration and readable through its
  `activeGeneration(name)`. Used to retire state and fence stale callbacks; never
  exposed as a tag or key component. `ReplicaStatusSnapshot.Entry.generation` is the
  same idea with its own private counter today; P10 aligns it on this identity. See §8.2.
- **Attempt** — one dispatch of an invocation (initial attempt `1`, incremented by retry).

### 3.4 The four clocks (kept separate)

| Clock | Owner | Concludes |
|---|---|---|
| **Waiter budget** | each caller (`X-Timeout-Ms`, else `spec.timeoutMs`) | only that waiter's HTTP wait |
| **Attempt deadline** | the dispatch attempt | the attempt (feeds the retry policy) |
| **Retry policy** | `maxRetries` | decides queue-vs-conclude on an attempt failure |
| **Max execution lifetime** | `ExecutionStore.inFlight` (`maxLifetime`) | the whole execution (administrative expiry) |

P05 and P06 separated the first two: a waiter's `.timeout(...)` now concludes only that waiter
(§5), and the attempt deadline is applied to a *mirror* of the raw transport future
(`ExecutionCompletionHandler.dispatchInternal`), so the deadline concludes the attempt and routes
the retry policy while the raw work — and the lease attributed to it — stays with the attempt until
it physically ends. What remains shared with the waiter's budget is the offload hop's remote budget
(`spec.timeoutMs` minus a 50 ms margin, `DefaultOffloadGateway.TIMEOUT_MARGIN_MS`), so that a remote
hop reports `504` before the local wait expires. The sync queue keeps its own
`syncQueueMaxQueueWait` item expiry, which is an execution-level deadline (row 10), not a caller
budget. The contract keeps the four clocks separate; the code does the same except for the
documented offload derivation.

## 4. Invariants

These are the plan's invariants, verbatim, in English. Every transition in §6 must preserve them.

- **I1** One execution has one shared terminal result; futures, polling and replay agree. A single
  waiter's timeout ends only that waiter's wait. The execution timeout is a distinct event.
- **I2** While a key is protected by its retention, removal/eviction of the result does not
  authorize a second execution. A result no longer available uses the tombstone contract, without
  redispatch.
- **I3** A terminal transition always concludes futures and local state. Resources are closed, or
  remain attributed to the work actually still active until its drain; they do not disappear from
  the counters for an HTTP timeout alone. Listeners and metrics are not part of the correctness
  transaction.
- **I4** Every attempt acquires exactly the resources it releases. The lease belongs to the attempt
  and the function generation, not the name alone. Retries and late callbacks do not release
  resources of other attempts.
- **I5** Admission of new work has finite count and byte limits even without queue/governor modules.
  Replays already existing do not consume a second execution admission; waiters nonetheless have
  their own limit.
- **I6** A payload that cannot be weighed within a bounded cost is not kept in the results cache.
  Key protection is maintained. The estimated weight is not presented as an exact measure of the
  whole heap.
- **I7** Removal and re-registration do not let old callbacks/refresh recreate state or modify the
  new generation's metrics/capacity. Retired state disappears after the release of the resources it
  owns.
- **I8** Executor queues, timers, callbacks and HTTP acquisitions are observable and bounded; every
  resource has a shutdown owner. One backend's error does not block the others indefinitely.
- **I9** Provider truth distinguishes available, stale and unavailable observation. A read error
  does not become `readyReplicas=0`.
- **I10** A partial deprovision error does not lose the ability to trace and clean up resources.
  Closing the Java context is not equivalent to deleting persistent containers recoverable at
  restart.

## 5. The R3 decision: per-waiter timeouts

**Today.** `ReactiveInvocationCoordinator.invoke` applies `.timeout(Duration.ofMillis(timeoutMs))`
to the shared future. On that timeout it calls `executionRecord.markTimeout()` — which stamps the
**shared** record `TIMEOUT`. `ExecutionCompletionHandler.completeExecution` then deliberately
preserves that `TIMEOUT` state while handing the real success to the long waiter, so a replay of
the same execution reads `TIMEOUT`. `openapi/core.yaml` documents the stable `TIMEOUT` terminal
state (`/v1/executions/{executionId}`, and the `408` of `:invoke`).

**Decision (I1).** A waiter's timeout concludes **only that waiter's wait**. The shared execution
continues; its real result is what a later poll/replay observes. Concretely, when a waiter's budget
expires:

- the waiter receives the documented timeout response (HTTP `408` and the `timeout` status — keep
  the existing HTTP format/status where already specified);
- the shared record, key, store, lease, budget and counters are **untouched**;
- only that waiter's subscription, timer and references are released;
- `suppressCancel=true` on the shared future stays, so a waiter detaching or timing out cannot
  cancel the shared work;
- the shared execution is concluded only by its own terminal events (success/error, retry
  exhausted/refused, shared execution timeout, function removal, administrative expiry, offload
  conclusion, shutdown).

**Compatibility constraints** (carried from P00's review of R3):

1. Keep the waiter-timeout HTTP format/status where already specified (the `408` description in
   `openapi/core.yaml`).
2. Document that a later poll/replay may first observe in-flight work (`queued`/`running`) and then
   the real result — never a fabricated `timeout` borrowed from another caller's budget.
3. Keep separate: caller budget (waiter), attempt deadline, retry policy, and max execution
   lifetime. Do not change the default retry count or make it non-configurable.
4. The `R3WaiterTimeoutSharedOutcomeRegressionTest` added in P00 asserts exactly this: a short
   waiter times out, the backend then returns success, and the long waiter **and** the replay both
   observe `success`. The ADR matches that branch.

**What still yields a shared `TIMEOUT` terminal.** An execution-level deadline — today the sync
queue's `syncQueueMaxQueueWait` item expiry (`QUEUE_TIMEOUT`) — concludes the whole execution with
`TIMEOUT`; every remaining waiter and replay observes it. A single caller's budget never does.

**Latency metrics stay distinct.** Metrics record three different durations and must keep doing so
after the R3 change: **wait latency** (this attempt's enqueue → dispatch, `function_queue_wait_ms`),
**attempt latency** (dispatch → completion of this attempt, `function_latency_ms`; censored when the
attempt never dispatched, e.g. a retry refused), and **execution latency** (original admission →
terminal conclusion, retries and their waits included, `function_e2e_latency_ms`; recorded exactly
once per invocation by the end-to-end-conclusion guard). A waiter timing out must not be classified
as a backend error, and its own wait must not be confused with the shared execution's duration.

## 6. State/event transition table

Legend for the effect columns:

- **Key** — the idempotency binding (`IdempotencyStore`), for keyed executions only.
- **Store** — `ExecutionStore.inFlight` (live record) and `ExecutionStore.outcomes` (archive).
- **Lease** — the dispatch-slot/capacity lease held by an attempt (I4).
- **Budget** — key quota (`maxKeys`), outcome byte budget (`maxOutcomeBytes`), admission/capacity
  slot, and (from P07) live-work/waiter byte-and-count budgets.
- **Cancellation** — the transport/callback handle and any timer/subscription owned by the attempt
  or waiter.

**Acceptance rule (stated once, applies to every row):** for each event, the *shared result* is the
same value observed by the shared future, by `GET /v1/executions/{id}` (live record or archive),
and by any replay of the same key. No row carries an implicit owner: the "Lease" and "Cancellation"
columns name who acquires and who releases/closes.

| # | Initial state | Event | Next state | Shared result | Key | Store | Lease | Budget | Cancellation |
|---|---|---|---|---|---|---|---|---|---|
| 1 | *(none)* | submit rejected | *(none)* | HTTP rejection (404 / 429 / 410 / 501); no execution exists | keyed: `abandonClaim` (pending claim removed); a **replay** of an existing key is served, never rejected | record removed if admission already created it (`abandonAdmission`) | none acquired | key quota returned; no outcome bytes; no slot | none |
| 2 | *(none)* | queued (admission accepted) | `QUEUED` | pending (no terminal) | `pending` → `published` (`publishClaim`) | live record present | none yet (acquired at dispatch, §8) | key quota held | none |
| 3 | `QUEUED` | dispatch | `RUNNING` | pending (no terminal) | `published` unchanged | live record (`markRunning`, `markDispatchedAt`) | slot acquired by the queue scheduler (`tryAcquireLease`, §11.1) | slot held | dispatch future handle registered |
| 4 | `RUNNING` | success | `SUCCESS` | the success envelope (output/status/headers/encoding) | `published` → `terminal` (`markTerminal`) | `settle`: outcome archived, record invalidated | slot released once (by the owner attempt) | outcome bytes retained if readable; key quota released at `ttl` | dispatch handle done |
| 5 | `RUNNING` | retryable error | `QUEUED` (attempt + 1) | pending (no terminal) | `published` unchanged (record keeps its original key across retries) | live record reset (`resetForRetry`) | failed attempt's slot released; the retry re-acquires at its own dispatch | slot returned, then re-acquired by the retry | failed attempt's handle done |
| 6 | `RUNNING` | retry refused (retry enqueue fails) | `ERROR` | the attempt's error | `published` → `terminal` | `settle` | slot released once | slot returned | handle done |
| 7 | `RUNNING` | retries exhausted | `ERROR` | the last error | `published` → `terminal` | `settle` | slot released once | slot returned | handle done |
| 8 | any non-terminal | waiter timeout | **unchanged** | that waiter: timeout response; shared result: unchanged | unchanged | unchanged | unchanged | unchanged | release **that waiter's** subscription/timer only |
| 9 | `RUNNING` | attempt timeout (attempt deadline) | `QUEUED` (retry) if attempts remain; else `ERROR` (timeout-classified) | pending, or the shared terminal | unchanged (retry) / `published` → `terminal` | unchanged / `settle` | failed attempt's slot released; retry re-acquires if any | slot returned | failed attempt's handle done |
| 10 | any non-terminal | execution timeout (shared deadline; today the sync-queue `syncQueueMaxQueueWait` expiry, `QUEUE_TIMEOUT`) | `TIMEOUT` | the shared timeout terminal | `published` → `terminal` | `settle` | slot released only if the attempt had dispatched (a still-queued item acquired none) | slot returned | dispatch handle done/cancelled if present |
| 11 | any non-terminal | administrative expiry (`maxLifetime`) | `ERROR` (`EXECUTION_EXPIRED`) | the fabricated expiry error | `published` → `terminal` | `settle` (eviction handler concludes, then settles) | release belongs to the acquired attempt, never to a name lookup | remote transport capacity drains on local cancellation; LOCAL capacity drains on actual work completion | cancellation is remembered before handle publication and follows DEPLOYMENT to its HTTP subscriber; shared wake-up futures remain untouched |
| 12 | any non-terminal | client cancellation (waiter detach/disconnect) | **unchanged** | that waiter ends; shared work continues | unchanged | unchanged | unchanged | unchanged | release **that waiter's** subscription only |
| 13 | `QUEUED` (removal of queued work; sync/async queue drain) | function removal | `ERROR` (`FUNCTION_REMOVED`) | the removal error | `published` → `terminal` | `settle` | none for still-queued work; a dispatched attempt releases once | slot returned | queued item removed from the queue |
| 14 | `RUNNING` (offload, no local slot) | offload success / offload failure | `SUCCESS` / `ERROR` | the remote result / the offload error | `published` → `terminal` | `settle` | none (offload never acquires a local slot) | no local slot; outcome bytes if retained | remote call done |
| 15 | any non-terminal | shutdown (context stop) | terminal per policy (each record settles) | the shared terminal for each record | `published` → `terminal` (as each settles) | `settle` per record | released per record by its owner | slot returned | executors, timers, proxies and clients closed by their shutdown owners (§8); persistent containers are **not** deleted |
| 16 | terminal (already settled) | duplicate / late callback | **unchanged** (idempotent) | unchanged terminal | unchanged | unchanged | no second release (released-attempts guard) | unchanged | late future completion is a no-op on a done future; state is never rewritten |

**Notes.**

- Terminal states (`SUCCESS`, `ERROR`, `TIMEOUT`) accept only row 16 (idempotent late callbacks)
  and replay/poll reads; there is no transition out of a terminal state.
- Row 8 and row 12 are the same rule applied to two waiter-side events (budget expiry and client
  disconnect): a waiter detaching never cancels the shared work, and its ending is invisible to the
  shared result (I1, I3).
- Row 9 is the "attempt timeout follows the retry policy" rule: it does **not** conclude the whole
  execution while attempts remain. It is distinct from row 10, which is the shared execution
  timeout.
- Rows 4–7, 10–15 all end in `settle`, which is the single terminal transition: protect
  the key with `IdempotencyStore.markTerminal` while the live record is still reachable,
  publish the record's selected terminal answer, archive the outcome if it fits, remove the
  live record, then notify best-effort observers. Key protection is an invariant, not a
  listener. Concurrent settlers publish the same answer; metrics cannot interrupt cleanup.
- The queue-wait timeout (row 10) is a shared conclusion because the queue's own policy decided the
  work had waited too long — it is not a single caller's budget.

## 7. Public key scope and remove/re-register

**Current public scope, verified in code and spec.**

- The idempotency key is the `Idempotency-Key` request header (`openapi/core.yaml`,
  `components/parameters/IdempotencyKey`), optional on `:invoke` and `:enqueue`.
- It is scoped **per function name**: `IdempotencyStore.compose` builds `functionName + ":" + key`.
  Two functions with the same key value are independent bindings.
- A key stays bound for the execution's whole life and for `nanofaas.execution-store.ttl` after it
  completes; within that window a replay returns the stored outcome, or `410 Gone` when the payload
  was evicted, instead of re-invoking (`openapi/core.yaml` IdempotencyKey description and the `410`
  responses).

**Generation identity is internal.**

- The claim token (`pending:*`), the execution id, the key's internal state
  (`pending`/`published`/`terminal`), `FunctionCapacityState` activation/reactivation, and
  `ReplicaStatusSnapshot.Entry.generation` are all internal. None is part of the public key scope,
  and none is exposed as a Prometheus tag (P09 forbids execution/generation ids as tags).
- The generation identity is what fences a stale callback or refresh from a removed incarnation
  (I7). It is **not** a way to bypass a still-valid tombstone.

**Remove / re-register of the same name.**

- Removing a function retires its queue state, capacity, metrics and (for managed deployments) its
  replica snapshot and provider state. It does **not** clear `IdempotencyStore` or `ExecutionStore`
  outcomes: the key binding and the archived result live on their own retention (`ttl`), independent
  of the function's registration lifecycle (I2).
- Re-registering the same name creates a new generation; old callbacks/refreshes cannot recreate
  state or touch the new generation's metrics/capacity (I7; `ReplicaStatusSnapshot` already fends
  them with a generation guard).
- A replay that arrives **after** remove/re-register and within the key's retention still resolves
  against the **old** binding: it returns the old outcome or `410 Gone` — never a fresh execution
  under the new generation. A fresh execution under the same name+key requires the retention to have
  elapsed (or a new key). This is the concrete meaning of "generation identity cannot bypass a
  still-valid tombstone by reusing the name."

## 8. Resource ownership matrix

For each resource: who **acquires** it, who **closes/releases** it, and what happens when the
method fails **before** the handle is published. This matrix is the reference for P02–P09; each task
must make its row true.

| Resource | Who acquires | Who closes/releases | Failure before the handle is published |
|---|---|---|---|
| **Execution record** (`ExecutionRecord`) | `InvocationExecutionFactory.newExecutionRecord` + `ExecutionStore.put` | `ExecutionStore.settle` (invalidate) or `ExecutionStore.remove` (abandonment) | admission failure → `abandonAdmission` removes the record and abandons the key claim (no orphan) |
| **Idempotency-key binding** (`StoredKey`) | `IdempotencyStore.acquireOrGet` / `claimIfMatches` (claim token) | `abandonClaim` (admission failed), `markTerminal` (terminal), Caffeine expiry (`ttl`/`maxLifetime`) | claim made but admission throws → `abandonClaim` removes the `pending` binding (R7: make the reservation atomic) |
| **Dispatch slot / capacity lease** | core direct/retry admission or queue scheduler (`tryAcquireLease`) | the acquired generation's lease, carried by the dispatch task; raw work completion releases before scheduling a retry | a synchronous dispatch failure releases the already-acquired lease; cancellation before handle publication is remembered; LOCAL capacity remains held until its raw work ends |
| **Shared completion future** (`CompletableFuture`) | `ExecutionRecord` construction | `ExecutionLifecycle.settle` publishes the terminal answer selected by the record (including exceptional offload failures) | waiter cancellation cannot cancel it (`suppressCancel=true`); a `complete()` on a done future is a no-op |
| **Outcome / archive entry** (`Outcome`) | `ExecutionStore.settle` (writes `outcomes`) | Caffeine weight/expiry eviction; `ExecutionStore.remove` (abandonment only) | key protection precedes outcome publication and live removal; declining retention therefore leaves a protected tombstone (I6, P04) |
| **Queue entry** (`InvocationTask` in the queue) | `enqueue` (async `QueueManager.enqueue`, sync `SyncQueueService.enqueueOrThrow`) | dequeue (`poll`/`removeReady`) or removal drain (`closeAndDrainQueued`, `drainRemovedFunction`) | enqueue returning false/refused → no entry; the caller's admission is aborted (row 1) |
| **Dispatch / transport future + HTTP acquisition** | dispatcher (`dispatcherRouter.dispatch*`) or offload gateway (`invokeRemote`) | normal completion; cancellation on shutdown/expiry (P06: keep the real cancellable handle) | dispatch throwing synchronously → `completeExecution` with a warm error result (release the lease already acquired on that path) |
| **Waiter subscription** (reactive subscriber + timer) | `ReactiveInvocationCoordinator.invoke` (`Mono.fromFuture(...).timeout(...)`) | waiter timeout, client cancel, or shared terminal completes the future | timeout/cancel releases only that waiter's subscription/timer; the shared future and record are untouched (I1) |
| **Function capacity/generation state** (`FunctionCapacityState`, `ReplicaStatusSnapshot.Entry`) | core function-registration listener (also without queue modules); optional modules consume the same registry | function removal, after its in-flight work drains | remove under load deactivates rather than deletes while slots are held; drain-then-delete (P09/P10) |
| **Deployment resources** (proxy, `FunctionState`, HttpServer, executor, HttpClient) | `ContainerLocalDeploymentProvider` at provision | `deprovision` (proxy closed first, then every container attempted) + context shutdown, which closes the proxies and leaves the containers recoverable | ownership is kept until the cleanup completes: the proxy is released on a guaranteed path, a replica leaves the map only once its container is confirmed gone, and what could not be removed is reported as `PartialDeprovisionException` (P08, R6) |
| **Executor/thread resources** (scheduler executor, replica-refresh pool) | `Scheduler`/`SyncScheduler.start`, `ReplicaStatusSnapshot` refresh pool | `SmartLifecycle.stop` (`shutdownNow` + shutdown); context shutdown | a `start` that throws shuts down the executor and clears the reference before rethrowing |
| **Metrics / meters** (Micrometer meters per function) | function register (`Metrics.registerFunction`, queue managers) | `Metrics.removeFunction` / queue removal listeners | a removed function's late event must not re-register meters (R8 → P09); removed-name sets retire with the generation |

### 8.1 Ownership by scope: execution, waiter, attempt, generation

The matrix above is per *resource*. This table is the same ownership seen per *scope* — the four
lifetimes that nest inside one invocation — and it is what P07–P11 must not blur. Read it together
with §6: a row there is one event; a row here is who owns what across all of them.

| Scope | Acquisition event | Release event | Cancellation | Retry | Shutdown |
|---|---|---|---|---|---|
| **Execution** (one invocation: record, shared future, key binding, admission quota) | admission accepted: `InvocationExecutionFactory.newExecutionRecord` + `ExecutionStore.put`, after the key claim is published. A replay of a live key reuses the execution and acquires nothing | `ExecutionStore.settle` — the single terminal transition; a failed admission uses `abandonAdmission` instead | concluded only by its own terminal events (§6 rows 4–7, 9–11, 13–15). A waiter detaching or timing out never cancels it (`suppressCancel=true`) | unchanged owner: same id, same key, same admission across attempts (`resetForRetry` returns it to `QUEUED`) | every live record settles per policy; none is left unconcluded (row 15) |
| **Waiter** (one HTTP caller attached to the execution) | `ReactiveInvocationCoordinator.invoke` subscribes to the shared future with that caller's own budget (`X-Timeout-Ms`, else `spec.timeoutMs`) | the shared terminal completes it, its own budget expires, or the client disconnects | waiter-local: only that subscription and timer are released; record, key, store, lease, budget and counters are untouched (rows 8 and 12, I1) | invisible: a retry neither concludes nor re-creates waiters; they keep waiting on the same shared future | the record's terminal answer concludes every attached waiter |
| **Attempt** (one dispatch of the execution) | `dispatchDirect` (core direct path), `dispatchWithLease` (core retry enqueuer) or the queue scheduler's `tryAcquireLease`: a `DispatchLease` under the function's generation, then the raw transport handle registered on the record. An attempt that never dispatched (offload, retry refused) owns no lease | the attempt's own completion, exactly once — `DispatchLease.release()` is idempotent. The release is driven by the **raw** transport future, never by the deadline mirror | `handle.cancel(true)` on the raw future (administrative expiry, shutdown); best-effort locally, never a promise of remote termination | the failed attempt releases its lease before the next attempt is scheduled; the retry acquires its own lease under whatever generation is active then, or fails admission | the owning record's settle releases the lease; executors, timers and clients close under the owners in the matrix above |
| **Generation** (one incarnation of a function name) | `FunctionCapacityRegistry.register` mints a `FunctionGeneration`, on first registration and again on re-registration after retirement | `remove` retires it; it closes — and its owner may drop it — only when the last resource it owns comes back (`GenerationPhase.CLOSED`) | retirement is not cancellation: it stops admission only. Work already in flight under it continues and stays accounted to it (I3, I7) | a retry admitted after re-registration belongs to the **new** generation; an old lease still releases only the old generation's slot (I4) | generations close as their resources drain with the context; no set of removed names or past generations outlives the functions themselves |

**Terminal future vs physical end.** These are two different moments and only the first is a state
transition. The terminal future is the shared answer: `settle` publishes it, and from that instant
replay, polling and every waiter agree. The physical end is when the work actually stops consuming
resources — a non-interruptible LOCAL handler still running, an HTTP exchange still draining, a
container still serving. Accounting follows the physical end, not the future: an attempt's lease is
released by the raw transport future's own completion, so capacity it still occupies is never handed
to a second attempt (I3, I4). Conversely the future is never held hostage by the physical end: an
attempt deadline, an administrative expiry or a shutdown concludes the execution while the raw work
is cancelled best-effort and its lease stays attributed until that work ends.

**Cancellation asked before the handle exists.** A cancellation can be requested before the
attempt's transport handle is published on the record — administrative expiry or shutdown racing a
dispatch that has not returned its future yet. The request is remembered on the record and applied
when the handle arrives; it is never silently dropped because it was early. The same rule holds for
DEPLOYMENT, where the handle reaches the record through the wake-up composition.

### 8.2 Generation identity and the active/retiring/closed protocol

A function *name* is not an identity: the same name can be removed and registered again while work
admitted under the previous registration is still draining. The identity is the generation, and
three small core contracts (`it.unimib.datai.nanofaas.controlplane.capacity`) carry it:

- `FunctionGeneration` — `functionName` + a monotonic id. Minted by `FunctionCapacityRegistry`,
  which is the core's mandatory capacity authority and therefore the one component that already
  retires and replaces a name's incarnation; `activeGeneration(name)` publishes it to the other
  owners. Internal only: never an execution id, a key component, or a metric tag.
- `GenerationPhase` — `ACTIVE -> RETIRING -> CLOSED`, plus the direct `ACTIVE -> CLOSED` when a
  generation is retired holding nothing. `CLOSED` is terminal.
- `GenerationLifecycle` — the reusable state machine: acquisition is admitted only while `ACTIVE`
  (with the owner's own ceiling applied atomically through `retainIfBelow`), and the transition to
  `CLOSED` is reported to exactly one caller so the owner's cleanup runs once. It holds no identity
  and fires no callback: the owner pairs it with its `FunctionGeneration` and runs its cleanup
  outside its own locks, which is what keeps lock order the same for every owner.

**Rules.**

1. Every per-function resource is attributed to the generation that acquired it, not to the name.
2. Retirement stops admission; it never disowns work in flight. Retired state disappears **after**
   the resources it owns are released, never before (I7).
3. A stale event — a late callback, a refresh reply, a delayed release — may close or release what
   *its own* generation owns. It may never recreate an entry for the name, and never mutate the
   generation that superseded it (`FunctionGeneration.supersedes` distinguishes the two).
4. Each owner keeps its own generation-scoped state in its own component: capacity in
   `FunctionCapacityRegistry`, meters in `Metrics` (P09), replica entries in `ReplicaStatusSnapshot`
   (P10), gates in the wake-up coordinator (P11). There is no global registry that remembers names
   or generations after their resources are gone — such a registry would be exactly the unbounded
   history R8 already reports.
5. A generation never bypasses a still-valid tombstone: re-registering a name does not make a
   replay of a retained key run again (§7).

`DispatchLease` carries a `FunctionGeneration` (P06 introduced the lease with a bare `long`; P20a
replaced it with the shared identity, no second lease concept), and `FunctionCapacityState`
implements the protocol above — a slot is one retained resource, `deactivate()` is the retirement
transition, and the state drains when its last slot returns.

### 8.3 Partial deprovision: pending removal vs operational rollback (P08)

A removal can end in three states, and only two of them may be presented as an outcome the caller
can trust.

1. **Removed.** The backend released everything. The catalog delete is committed last.
2. **Pending removal.** The backend reports a `PartialDeprovisionException` naming what it could not
   release. Resources were really lost from the control plane's reach, so no rollback is claimed:
   the catalog entry stays (it is what keeps the leftovers traceable and what makes a retried
   `DELETE` resume the cleanup), the registration listeners are **not** replayed — capacity, queues
   and meters retire with the generation that owns those resources (I7) — and every path into the
   function is refused with `409 FUNCTION_REMOVAL_PENDING`: invoke, enqueue, patch, replicas and
   re-registration. The endpoint is already closed, so admitting anything would only produce a
   transport error the caller cannot interpret.
3. **Operational rollback.** Allowed only when nothing needed was lost: either the deployment was
   never touched, or it was fully deprovisioned and `reconcile` rebuilt it against the persisted
   backend and replica target — a rebuild that probes the resources back before the function is
   declared live again. Closing the proxy and then declaring an operational rollback is forbidden;
   that is why the container backend closes the proxy *first* on the removal path and reports every
   later failure as pending removal.

The distinction lives in one place per side: `ContainerLocalDeploymentProvider.deprovision` decides
whether the outcome is partial, and `FunctionService.remove` routes a `PartialDeprovisionException`
to `holdPendingRemoval` and everything else to `rollbackRemoval`.

Retry and restart both work from the resources themselves. A second `deprovision` resumes over the
tracked state; after a restart there is no tracked state, and the backend rediscovers its containers
from their managed labels. Closing the Java context closes the proxies and leaves the containers
alone — closing the context is not deleting the deployment (I10).

**A restart drops the pending-removal fence.** Pending removal is process-local: it is a name held
in memory by `FunctionService`, not a flag persisted with the catalog entry. The entry itself *is*
persisted — that is what keeps the leftovers traceable — so a control plane that restarts while a
function is in pending removal restores it like any other function: `FunctionCatalogRestorer`
reconciles it through the normal path, the backend adopts the surviving containers, recreates the
replicas the partial delete had already removed, and publishes a fresh endpoint. The function is
then in case 3 above — rebuilt and verified, serving again under a new generation — and every 409
of case 2 is gone with the process that held it. The operator's DELETE does not survive the
restart: **if the cleanup is still wanted, the DELETE must be re-issued after the restart.** This is
the case an operator is most likely to meet, because a restart is a natural reaction to the broken
container runtime that caused the partial delete in the first place. It is a consequence of the
single-pod, in-memory constraint, not an oversight: making the fence survive would require a
persisted field on the deployment metadata and a restore path that refuses to rebuild, which this
contract does not require today.

## 9. Conformance: current code vs this contract

The contract above is the target. The baseline at `61d72e73` deviates at these points; each is
closed by the named task.

| Contract row / invariant | Deviation today | Closed by |
|---|---|---|
| §5 / I1, rows 8, 10 | `ReactiveInvocationCoordinator` marks the shared record `TIMEOUT` on a waiter timeout (R3) | P05 (this ADR makes the decision explicit; tests activated in P05) |
| rows 4–7, 10–15 / I2 | `ExecutionStore.settle` publishes outcome → invalidates record → notifies listeners; the terminal-key notification can be delayed past an eviction, reopening the key (R2) | P04 |
| rows 8, 14 / I3 | offload completion returns early on a terminal record without settling the future/store (R4) | P04/P05 |
| row 3, 13 / I4 | direct completion releases a name-based slot that the direct path never acquired when sync is disabled (R5) | P06 |
| §8 key row / I5 | `maxKeys` is checked non-atomically before `putIfAbsent` (R7) | P03 |
| rows 1, 2 / I5 | no-queue direct admission has no count/byte bound (direct admission admits unbounded concurrency) | P06/P07 |
| §8 outcome row / I6 | `OutcomeWeigher` prices deep/wide payloads near zero and retains them (R1) | P02 |
| §7 / I7 | removed-name sets in `Metrics`, sync-queue metrics, and `ReplicaStatusSnapshot` entries accumulate (R8); offload meters are never deregistered | P09/P10 |
| §8 deployment row / I10 | ~~a deprovision adapter throw loses the proxy reference and provider state (R6)~~ **closed by P08**: pending removal, see §8.3 | P08 |
| §8 transport row / I3 | administrative expiry releases bookkeeping but holds no handle to cancel the dispatch (work can outlive its released capacity) | P06 |

No new state is introduced by this ADR. Any future state addition must carry a defined transition
into and out of it, an owner, and an unchanged shared-result guarantee.

**Status at `92aae4e9`** (recorded by P20a, after P00–P06 and their review fixes). Delivered: R1
(P02), R7 (P03), R2/R4 (P04), R3 and the per-waiter timeout (P05), R5, mandatory core capacity,
attempt-scoped leases, the cancellable transport handle and the per-attempt deadline (P06). Still
open, in the order the campaign now runs them: unbounded direct admission and the aggregate
live/payload/waiter budgets (P07), R8 — which keeps two explicitly red regressions until
P09 removes the historical sets and P10 removes the snapshot entries — and the shared wake-up (P11).

## 10. Residual inventory after P06 (recorded by P20a)

P06 moved capacity into the core and introduced the attempt-scoped lease, deliberately leaving
name-based adapters behind so the queue modules could migrate one at a time. This is the verified
inventory of what exists today, its **real** consumers (read from the call sites, not assumed), and
what happens to it. Nothing is removed here: removal is P20b's and P21's job, once the consumers
have a port to move to.

| Item | Real consumers today | Verdict |
|---|---|---|
| `DispatchLease`, `DispatchAttempt` | `ExecutionRecord`, `InvocationTask`, `ExecutionCompletionHandler` (`dispatch`/`dispatchDirect`/`dispatchWithLease`), `ExecutorBackedInvocationEnqueuer`, `QueueManager`/`Scheduler`, `SyncQueueInvocationEnqueuer`/`SyncScheduler` | **Reuse.** P20a made the lease carry `FunctionGeneration` instead of a bare `long`; no second lease concept is introduced by any later task |
| `FunctionCapacityRegistry.tryAcquireLease(name, concurrency)` | core direct admission and the core-only retry enqueuer | **Reuse** |
| `FunctionCapacityRegistry.tryAcquireLease(name, expectedState, onReleased)` | both queue schedulers (bind the lease to the exact state that supplied the queued work) | **Reuse**; P20b can drop the `expectedState` argument once modules identify the incarnation by generation instead of by state object |
| `FunctionCapacityRegistry.tryAcquireSlot(name)` and the whole `InvocationEnqueuer.tryAcquireSlot` chain (`QueueBackedEnqueuer`, `SyncQueueInvocationEnqueuer`, `ExecutorBackedInvocationEnqueuer`, `NoOpInvocationEnqueuer`, `QueueManager.tryAcquireSlot`) | **none in production** — verified: every remaining call site is a test or a test double that simulates `tryAcquireLease` | **Remove** in P20b, together with the port method |
| `FunctionCapacityRegistry.releaseSlotAndGetHoldNanos(name)` via `InvocationEnqueuer.releaseDispatchSlot` | live but effectively inert: `ExecutionCompletionHandler.releaseAttemptCapacity` returns early once the transport owns the capacity, so it fires only for a dispatch with no lease (missing/terminal record). The registry's `leased` bookkeeping additionally prevents it from consuming a lease-owned slot | **Remove** in P20b with the last name-based release; the `leased` identity map exists only to make this adapter safe and goes with it |
| `FunctionCapacityRegistry.state(name)` | `SyncQueueInvocationEnqueuer.hasAvailableSlot`/`tryAcquireLease` | **Complete**: P20 replaces it with a read-only capacity/observation port; handing modules the mutable state object is what a port removes |
| `FunctionCapacityRegistry.hasGeneration(name)` | `SyncQueueService` (two runtime-activation guards) | **Complete**: it answers "any generation, including one that is only draining". `activeGeneration(name) != null` is the question those guards actually ask; P20b switches them |
| `configuredConcurrency` / `effectiveConcurrency` / `setEffectiveConcurrency` / `inFlight` (by name) | governor and queue metrics | **Reuse** until P20's `InvocationObservations` read-only port; the governor regulates the effective value, the core owns the state |
| `addCapacityListener` | sync-queue wake-up on a capacity opening | **Reuse** |
| `FunctionCapacityState.incrementInFlight()` (no ceiling) | `QueueManager.incrementInFlight`, itself called only from tests | **Remove** in P20b: an increment that bypasses admission has no place once every acquisition is a lease |
| public `FunctionCapacityState(int)` / `(int, LongSupplier)` constructors | the test-facing `FunctionQueueState` constructors only; production wires `capacityRegistry.register(...)` | **Remove**/narrow in P20b so a generation can only be minted by the registry |
| `ExecutorBackedInvocationEnqueuer`'s test-only `Consumer` constructor (releases the lease right after dispatch) | the A3 retry round-trip tests | **Complete** in P07, which gives the no-queue retry path its own bound and regression |
| `ReplicaStatusSnapshot.Entry.generation` (private counter) | replica snapshot refresh/invalidate | **Complete** in P10: same idea, own counter; align it on `FunctionGeneration` and the phase protocol |
| removed-name sets in `Metrics` and `SyncQueueMetrics`, lazily created and offload meters | metric registration/removal | **Complete** in P09: replace the ever-growing sets with generation owners (R8 stays red until then) |

## 11. P20b: consumer ports and completed residual migration

The inventory in §10 is historical. P20b is a delta from `21b361cd9480c6198675935a8644b5db75d96c24`,
after the independently approved P19 baseline `d93b68cdf1cca6e7af17e1c641c8e236c80d0fca`.
It keeps the existing immutable task, execution lifecycle, capacity registry and lease owners.
There is no new SPI Gradle project, execution/deployment JAR, scheduler per HTTP response mode,
module-ID change or retry-default change. Packaging extraction remains P21.

### 11.1 Actual consumer contracts

| Consumer | Port and consumed operations | Ownership boundary |
|---|---|---|
| `InvocationService` | `InvocationEnqueuer.supportsAsync`, `enqueue`, `isQueueFull` | Initial API admission only; response mode grants no capacity |
| `ReactiveInvocationCoordinator` | `InvocationEnqueuer.queueStrategy`, `enqueue`; existing `SyncQueueGateway.enabled` / `enqueueOrThrow` | `FUNCTION_QUEUE` routes both SYNC and ASYNC through async-queue's one function strategy; sync gateway activation remains an independent runtime choice |
| `ExecutionCompletionHandler` | `RetryScheduler.enqueue` | Schedules the next already-admitted attempt, without implying public async or queued initial admission |
| async `Scheduler`, `SyncScheduler`, core retry executor | `InvocationDispatch.dispatch(InvocationTask)` | Task transports its existing `DispatchOwnership`; schedulers no longer depend on HTTP orchestration |
| `InvocationTask`, `DispatchAttempt`, completion/physical work | `DispatchOwnership.generation`, `release`, `isReleased` | Existing `DispatchLease` implements the minimal handle; no concrete lease class is exported through task/consumer signatures and no owner is recreated |
| `QueueManager`, `SyncQueueService`, sync enqueuer/workload source | `DispatchCapacity.register`, `remove`, `state`, `activeGeneration`, `retainsGeneration`, generation-bound `tryAcquireLease`, concurrency readings/control, `addCapacityListener` | Registry remains the authority; `CapacityView` is read-only. Only an owned handle releases a slot |
| async lifecycle listener, sync queue, both schedulers | `QueueLifecycle.removed`, `expired`, `rejected`; sync also `inFlightExecutionIds`, `onExecutionGone` | Existing `ExecutionStore` resolves IDs and delegates canonical settlement to its attached `ExecutionLifecycle`. Modules cannot mutate records, complete futures, archive outcomes or release capacity by name |
| governor, autoscaler `ScalingMetricsReader` | `InvocationObservations.snapshot` | Immutable totals and the shared `FunctionGeneration`, read from existing metric owners only; no registration or mutable Timer/Counter API |
| governor policies and autoscaler backlog readings | existing `WorkloadMetricsSource` / `WorkloadCapacityController` | Queue readings and the governor's explicit capacity-control authority remain separate from read-only invocation observations |

`InvocationEnqueuer` now has only initial-admission capabilities: `QueueStrategy.DIRECT` or
`FUNCTION_QUEUE`, independent `supportsAsync`, enqueue and an advisory full-queue hint. The old
overloaded `enabled()` and name-slot methods are gone. async-queue implements both admission
and retry contracts. sync-queue implements retry and `QueuedDispatchCapacity`; its existing
gateway controls initial sync admission and runtime toggles. The core provides direct/no-async
admission separately from a bounded retry-only executor. Conditional bean creation is by
contract, not by inferring capabilities from module names; queue providers suppress the unused
fallback retry pool.

### 11.2 Removed bridges and active-generation semantics

Removed: registry name-only `tryAcquireSlot` / `releaseSlotAndGetHoldNanos`, `releaseAnySlot`,
the `leased` identity map, concrete-state acquisition adapter, and `hasGeneration`; enqueuer
and queue increment/decrement/name-slot bridges; unbounded `FunctionCapacityState.incrementInFlight`
and standalone state constructors; record name-release attempt sets/direct-admission flags;
the production test-only core retry constructor; redundant `dispatchWithLease`, API-service
scheduler dispatch and unused `DispatchResult` forwarding overloads. State mutation is now
package-private to the core. Tests drive real owned leases instead of reviving these adapters.
The two `InvocationService.completeExecution(InvocationResult, ...)` methods remain because
`InvocationController` actually uses them for the runtime callback API.

The P20b source inventory refined §10's `hasGeneration` description: surviving sync call sites
also clean up removal fences/locks, so replacing all of them with a bare active check would
prematurely forget physical drain. Activation uses `activeGeneration != null`; cleanup may
additionally ask whether the fence's **exact** generation is still tracked via
`retainsGeneration(fence.generation)`. No historical-presence lookup can admit new work. Late
release diagnostics compare the captured identity with the active one before writing meters.

### 11.3 Observation and event semantics

Snapshots never register meters, resurrect removed function labels or retain execution owners.
Service totals preserve the final observed attempt's dispatch-to-completion duration, excluding
earlier retried attempts and censored/undispatched outcomes. End-to-end totals preserve original
admission-to-terminal duration, including retries and queue waits, once per logical invocation.
An independent waiter timeout is not an execution conclusion. Dispatch counts include attempts
and retries. Durations derive from monotonic execution timestamps and are reported in milliseconds;
concurrent counts/totals are approximate samples within one registration. An absent registration
has null identity and zero totals. Control-loop deltas never compare different generations.
Governor policies/periods and autoscaler policies/periods remain independent. Governor cycles
serialize with removal cleanup so a sampled cycle cannot repopulate retired controller state.

Queue events conclude through the existing owner, ignoring stale attempts and preserving an
already selected terminal result. Two defects were reproduced during migration: sync queue
expiry returned `QUEUE_TIMEOUT` to waiters but archived a null error; scheduler dispatch rejection
released a slot but left the accepted execution live. Expiry now archives the same selected
`QUEUE_TIMEOUT` while retaining `TIMEOUT` state; rejection now settles `DISPATCH_REJECTED`.
Input-quota backpressure still requeues. Logical settlement does not release physical work's
lease/input before actual drain. I1–I10 and protected-key/replay semantics remain binding.

Architecture RED/GREEN, per-consumer lifecycle tests, profile integrations, suite results,
graph audit and commit receipts are recorded in lifecycle `STATO.md` and
`.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P20b-report.md`.

## 12. P21: the mandatory contract library and the ports it forced

P21 is a delta from `2ddd839d` (P20b closure). It extracts the mandatory Gradle project
`:control-plane-spi` at `platform/control-plane-spi`, and it is the task that made every optional
module stop compiling against the core implementation. No module ID changed, no optional module
was added or removed, and no execution or deployment-management JAR was created — the decision in
the plan's section 2 against those JARs still stands.

### 12.1 What the contract library contains, and why that list is closed

The library holds the P20 ports, the lifecycle and capacity views, the provider and replica DTOs,
and the `RuntimeConfigExtension` contract. It holds no store, no record, no mutable capacity, no
registry, no controller, no executor and no autoconfiguration; those stay in the core.

The list is closed by the build graph rather than by a convention. `:control-plane-spi` declares
exactly three dependencies — `:common` for the shared wire models, `reactor-core` because a gateway
signature returns a `Mono`, and `slf4j-api` because two scheduler support classes log — and it
does not depend on `:control-plane`. A reference from a contract to a core implementation therefore
does not compile. Symmetrically, each optional module's compile classpath lost `:control-plane`
entirely, so a module that reached back into an implementation would fail to build. `SpiPurityTest`
is the secondary guard for the case the build file itself changes; both of its whitelist rules were
verified to fail against a controlled Spring reference before being accepted.

Moved contracts keep their existing fully-qualified names, so `:control-plane-spi` and
`:control-plane` share several package names across two jars. No `module-info.java` exists in this
repository, so these are unnamed modules and the split is legal on the classpath. The benefit is
that AOT and native hints, reflect-config, ArchUnit package rules, OpenAPI composition and
`module.properties` continue to address these types by the names they already use, and the diff
stays a set of moves plus new ports instead of an import rewrite across the whole tree. A future
JPMS migration would have to unsplit those packages first; that is a recorded consequence.

### 12.2 The ports the extraction forced

Five module dependencies on concrete core classes could not be satisfied by moving a contract,
because the class itself is an implementation the core must keep owning. Each became a port
carrying exactly the operations the consumer inventory proved are used, and each core class gained
the interface additively, with no existing signature changed.

| Port | Core implementor | Operations, and why the port stops there |
|---|---|---|
| `registry.FunctionCatalogView` | `FunctionRegistry` | `listRegistered` only. The autoscaler and the governor iterate the catalog; they must not be able to mutate the catalog they are reading |
| `registry.ManagedReplicaControl` | `ManagedDeploymentCoordinator` | `observeReplicaStatus`, generation-fenced `setReplicas`, `generationOf`. Reading never blocks on the provider and never reports absence as zero replicas (I9); mutation cannot land on the registration that replaced the one a decision was computed for |
| `deployment.DeploymentWakeUpControl` | `DeploymentWakeUpCoordinator` | `scaleDownIfUnprotected`, `removeFunctionState`. The downscale is offered as a guarded operation rather than a question a scaler could ask and then act on stale, so a scaler cannot undo an in-flight invocation's own wake-up |
| `offload.OffloadMeters` (+ `OffloadMeterLease`) | `Metrics`, `Metrics.OffloadMeterLease` | `offloadMeters`, and the lease's `subscribed`/`failed`/`close`. The gateway counts attempts; it gains no ability to register or remove meters, and a retired generation's lease drops its updates instead of resurrecting a series (I7) |
| `capacity.AdmissionLimitsControl` | new `service.HotAdmissionLimits` over `RateLimiter`, `InvocationCapacity`, `WaiterCapacity` | `limits`, `updateLimits`, exchanged as plain values. A runtime-configuration extension proposes numbers; the quotas keep their own validation, so the rule that a per-function share cannot exceed its global budget is not copied into a second place |

Where a port lives is part of its design here, because the core's package-cycle rule is binding.
`ManagedReplicaControl` names a `RegisteredFunction`, and `registry` already depends on
`deployment`, so the port sits in `registry`; placing it beside the deployment DTOs closed a
`deployment -> registry -> deployment` cycle, which is how that placement was caught.
`AdmissionLimitsControl` sits with the quotas it describes rather than in `config`, for the same
reason, and its implementor sits in `service` beside the `RateLimiter` it needs — declaring it in
the capacity configuration had made a minimal capacity slice require the service layer, which the
existing slice test refused.

Two contracts had to change to be nameable from a library with no Web dependency.
`ImageValidationException` now carries the raw status code instead of a Spring `HttpStatus`; the
only reader is the core exception handler, and the 422/424/503 responses are unchanged.
`QueuedInputLease` now wraps a release action instead of the core retained-input reference, with
core constructing it from that reference's `close`; no module ever named this type.

### 12.3 Capacity defaults the modules no longer fabricate

`QueueManager` and `SyncQueueService` each had constructors that created a
`FunctionCapacityRegistry` of their own. Production never used them — the comment in
`SyncQueueService` said as much — and they were the last reason either queue module named a
concrete capacity owner. They are removed, and the module tests that relied on them now construct
the capacity explicitly, exactly as the Spring wiring already did. No module creates a capacity
owner any more; the core remains the single authority.

### 12.4 What P21 deliberately did not do

Module *test* sources keep `testImplementation project(':control-plane')`: the plan allows module
integration tests to run against the core consumer, and migrating them is not this task's subject.
Strengthening the architecture rules beyond the moved symbols, consolidating the deployment beans
and finishing the capacity drain out of `workload-metrics` remain P22.

## 13. P22: bean ownership, architectural boundaries, and the separations kept

P22 is a delta from `ecd21415` (P21 closure). It closes the campaign's structural work: every
asynchronous resource has exactly one owner, a control plane without a managed deployment
provider builds none of the machinery only a managed deployment would use, and the boundaries
the previous tasks established are now asserted by rules that were each proven able to fail.

### 13.1 The project graph

```
          common  ────────────────┐         (wire and runtime models)
            ▲                     │
            │                     ▼
            │            control-plane-spi   (contracts: ports, lifecycle views,
            │                     ▲           provider/replica DTOs, runtime-config)
            │                     │
            │        ┌────────────┴────────────┐
            │        │                         │
       control-plane │                    platform/modules/*   (optional, selected at build)
      (implementation,│                    async-queue · sync-queue · autoscaler ·
       single owner of│                    concurrency-control · offload · runtime-config ·
       store, record, │                    build-metadata · k8s/container providers
       capacity,      │                         │
       registry,      └──── runtimeOnly ────────┘   (packaging only: the selected modules
       lifecycle)                                    are on the core's runtime classpath)

       workload-metrics  ◄──── queue modules, autoscaler, concurrency-control
       (observability contracts only; the core does not depend on it at all)
```

Compile edges point one way only. `control-plane-spi` sees `common` and nothing else of ours,
so a contract cannot reference an implementation. No module sees `control-plane`, so a module
cannot either. The core depends on the selected modules at runtime only, for packaging, which is
why adding a module never inverts a compile dependency. Module *test* sources do see the core:
module integration tests legitimately run against the consumer, and that is the only direction
in which the two meet.

### 13.2 Bean ownership

| Resource | Owner | Exists when |
|---|---|---|
| replica snapshot and its two refresh pools | `ReplicaStatusSnapshotConfiguration`, imported by the managed orchestration | a `ManagedDeploymentProvider` bean exists |
| wake-up executor, wake-up timeout scheduler, wake-up coordinator, wake-up gate | `ManagedDeploymentOrchestration` | a `ManagedDeploymentProvider` bean exists |
| `ManagedDeploymentCoordinator` | the managed orchestration, or `UnmanagedDeploymentDefaultsAutoConfiguration` built on a snapshot owning no pool | always |
| `DeploymentReadiness` | the wake-up gate when managed, `DeploymentReadiness.immediate()` otherwise | always |
| core retry executor | `InvocationEnqueuerAutoConfiguration` | no queue module supplies a `RetryScheduler` |
| queue executors and schedulers | the selected queue module | that module is selected |
| hot admission limits | `ServiceDefaultsConfiguration` | always |

Two fallbacks that used to be built inside consumers are gone. The dispatch path treated a
missing wake-up gate as a `null` to branch on; it now receives a readiness value, and asks
`isImmediate()` to keep the LOCAL/EXTERNAL route as short as the one P19 measured. More
importantly `FunctionService` used to construct its own `ManagedDeploymentCoordinator` when no
bean existed, and that constructor creates a replica snapshot owning two refresh pools — an
object outside the context, so nothing ever closed it. Both fallbacks are now beans.

Conditions are evaluated in the right order because both new configurations are
auto-configurations rather than component-scanned classes. `@ConditionalOnBean` only sees what is
registered when it runs, and component-scanned classes are processed before any
auto-configuration; a scanned configuration would have asked for a provider before any provider
module had published one, and disabled managed deployment everywhere. The same reasoning was
already recorded on `InvocationEnqueuerAutoConfiguration`.

### 13.3 Where the wiring lives, and why not in `deployment`

The managed orchestration is wired from the `service` package, not from `deployment`. The wake-up
gate is a service-layer class, and `deployment` may not depend on `service`; the gate cannot move
down either, because it reads the function catalog and `registry` already depends on
`deployment`. Wiring it from `service` is what keeps the package graph acyclic, and the cycle rule
is what established that — the first placement, in `config`, closed
`config -> service -> config` through existing test edges. The snapshot's own configuration stays
in `config` under its original name, without a stereotype annotation so that it is imported
rather than scanned: that keeps its bean conditional while leaving its ownership assertable on
its own.

### 13.4 Rules that can fail

| Rule | Where | Controlled violation that failed it |
|---|---|---|
| the SPI depends only on `common`, reactor and slf4j | `SpiPurityTest` | a Spring `HttpStatus` named from a contract (P21) |
| a module consumes contracts, never core implementations | each module's `ArchitectureTest` | `Metrics` named from the autoscaler, with the core added to its classpath |
| only the execution package publishes a terminal state | `CoreArchitectureTest` | a second `publishTerminal()` call from the completion handler |
| the `controlplane` namespace belongs to the core and the contract library | `CoreArchitectureTest` | covered by `CoreArchitectureSourceTest`, which also asserts the two predicates reject each other's paths |

Every one was injected, observed red, and reverted; none of the deliberately incorrect sources
was committed. The module rule is expressed by type name rather than by package because the core
and the contract library share package names on purpose: `controlplane.service` holds both the
`InvocationEnqueuer` contract and the `Metrics` implementation.

### 13.5 Separations kept, and the evaluations now closed

- **The two queue modules stay separate.** `async-queue` provides per-function queues and the
  scheduler shared by SYNC and ASYNC; `sync-queue` provides synchronous admission with a global
  depth and a wait estimate. They are alternative strategies, they conflict in their descriptors,
  and merging them would fuse two admission policies into one module whose behaviour would depend
  on configuration rather than on selection. Not merged.
- **Autoscaler and concurrency-control stay separate.** They consume the same observations and the
  same generation identity, but one changes replica count and the other changes per-replica
  concurrency, on their own control periods. Merging them would couple two independent control
  loops. Not merged.
- **The two deployment providers stay mutually exclusive adapters.** Each owns its image
  validator and its client. Not merged.
- **No `execution` or `deployment-management` JAR was created.** The decision recorded in the
  plan's section 2 stands: those boundaries are packages and configurations verified inside the
  core, and a separate JAR would add neither a deployment requirement nor an independent
  consumer. This closes that evaluation rather than leaving it as forgotten work.
- **`workload-metrics` keeps only observability contracts.** The mutable capacity it once owned
  moved into the core in P06 and P20b; the core no longer depends on this project at all, and the
  one remaining unused bridge alias (`recordDispatchSlotBlocked`) is deleted. What stays —
  `WorkloadMetricsSource`, `WorkloadCapacityController`, `WorkloadMetricsBinder`,
  `WorkloadMetricNames` — has real consumers in the queue modules, the autoscaler and the
  governor. `WorkloadDiagnostics`, which no composition ever recorded into, was deleted later.
