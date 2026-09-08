# ADR 0001 — Execution binding lifecycle contract

- **Status:** Accepted (normative contract for the control-plane lifecycle campaign)
- **Date:** 2026-09-08
- **Campaign:** control-plane lifecycle, memory and modularity
- **Applies to:** `platform/control-plane` core + optional modules (`async-queue`, `sync-queue`, `offload`, `container-deployment-provider`, …)
- **Reviewed against:** `61d72e73` (P00, branch `control-plane-lifecycle-memory`); analyzed revision `1d9e2f55` (`main`, v0.21.0)
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
transition table, the public key scope, and a per-resource ownership matrix. It changes no
production code; it is normative for the code changes that follow.

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
- **Generation identity** — internal identity for one incarnation of a function name
  (`FunctionCapacityState` activation/reactivation, `ReplicaStatusSnapshot.Entry.generation`).
  Used to retire state and fence stale callbacks; never exposed as a tag or key component.
- **Attempt** — one dispatch of an invocation (initial attempt `1`, incremented by retry).

### 3.4 The four clocks (kept separate)

| Clock | Owner | Concludes |
|---|---|---|
| **Waiter budget** | each caller (`X-Timeout-Ms`, else `spec.timeoutMs`) | only that waiter's HTTP wait |
| **Attempt deadline** | the dispatch attempt | the attempt (feeds the retry policy) |
| **Retry policy** | `maxRetries` | decides queue-vs-conclude on an attempt failure |
| **Max execution lifetime** | `ExecutionStore.inFlight` (`maxLifetime`) | the whole execution (administrative expiry) |

Today these are partly conflated: the single `timeoutMs` drives both the waiter's `.timeout(...)`
and (minus a 50 ms margin) the offload hop's remote budget; the sync queue has its own
`syncQueueMaxQueueWait` item expiry. The contract keeps them separate; the code must do the same
(P05/P06).

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
| 3 | `QUEUED` | dispatch | `RUNNING` | pending (no terminal) | `published` unchanged | live record (`markRunning`, `markDispatchedAt`) | slot acquired by the queue scheduler (`tryAcquireSlot`) | slot held | dispatch future handle registered |
| 4 | `RUNNING` | success | `SUCCESS` | the success envelope (output/status/headers/encoding) | `published` → `terminal` (`markTerminal`) | `settle`: outcome archived, record invalidated | slot released once (by the owner attempt) | outcome bytes retained if readable; key quota released at `ttl` | dispatch handle done |
| 5 | `RUNNING` | retryable error | `QUEUED` (attempt + 1) | pending (no terminal) | `published` unchanged (record keeps its original key across retries) | live record reset (`resetForRetry`) | failed attempt's slot released; the retry re-acquires at its own dispatch | slot returned, then re-acquired by the retry | failed attempt's handle done |
| 6 | `RUNNING` | retry refused (retry enqueue fails) | `ERROR` | the attempt's error | `published` → `terminal` | `settle` | slot released once | slot returned | handle done |
| 7 | `RUNNING` | retries exhausted | `ERROR` | the last error | `published` → `terminal` | `settle` | slot released once | slot returned | handle done |
| 8 | any non-terminal | waiter timeout | **unchanged** | that waiter: timeout response; shared result: unchanged | unchanged | unchanged | unchanged | unchanged | release **that waiter's** subscription/timer only |
| 9 | `RUNNING` | attempt timeout (attempt deadline) | `QUEUED` (retry) if attempts remain; else `ERROR` (timeout-classified) | pending, or the shared terminal | unchanged (retry) / `published` → `terminal` | unchanged / `settle` | failed attempt's slot released; retry re-acquires if any | slot returned | failed attempt's handle done |
| 10 | any non-terminal | execution timeout (shared deadline; today the sync-queue `syncQueueMaxQueueWait` expiry, `QUEUE_TIMEOUT`) | `TIMEOUT` | the shared timeout terminal | `published` → `terminal` | `settle` | slot released only if the attempt had dispatched (a still-queued item acquired none) | slot returned | dispatch handle done/cancelled if present |
| 11 | any non-terminal | administrative expiry (`maxLifetime`) | `ERROR` (`EXECUTION_EXPIRED`) | the fabricated expiry error | `published` → `terminal` | `settle` (eviction handler concludes, then settles) | slot released only if `dispatchedAt` is set (a queued item acquired none) | slot returned | bookkeeping closed; **no transport cancellation handle today** (closed by P06) |
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
- Rows 4–7, 10–15 all end in `settle`, which is the single terminal transition: publish the
  outcome, invalidate the live record, then (and only then) notify terminal listeners — including
  the `IdempotencyStore.markTerminal` listener. P04 makes this order the authoritative, race-free
  one that closes R2.
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
| **Dispatch slot / capacity lease** | queue scheduler at dispatch (`tryAcquireSlot`: `Scheduler.acquireNext`, `SyncScheduler.tickOnceInternal`) | `ExecutionCompletionHandler.releaseDispatchSlotOnce` via `InvocationEnqueuer.releaseDispatchSlot`; guarded by the record's released-attempts set | `dispatch` throwing synchronously (before the future) never acquired a slot, so nothing to release; an acquired slot released exactly once by its owner attempt (I4; closes R5) |
| **Shared completion future** (`CompletableFuture`) | `ExecutionRecord` construction | completion path (`publishFinalCompletion`, `completeOffloadedExecution`, `handleAdministrativeExpiry`, queue removal/timeout, offload failure) | waiter cancellation cannot cancel it (`suppressCancel=true`); a `complete()` on a done future is a no-op |
| **Outcome / archive entry** (`Outcome`) | `ExecutionStore.settle` (writes `outcomes`) | Caffeine weight/expiry eviction; `ExecutionStore.remove` (abandonment only) | outcome publication precedes the key's terminal notification, so a non-retained outcome (I6) still leaves a protected tombstone (P04 closes R2) |
| **Queue entry** (`InvocationTask` in the queue) | `enqueue` (async `QueueManager.enqueue`, sync `SyncQueueService.enqueueOrThrow`) | dequeue (`poll`/`removeReady`) or removal drain (`closeAndDrainQueued`, `drainRemovedFunction`) | enqueue returning false/refused → no entry; the caller's admission is aborted (row 1) |
| **Dispatch / transport future + HTTP acquisition** | dispatcher (`dispatcherRouter.dispatch*`) or offload gateway (`invokeRemote`) | normal completion; cancellation on shutdown/expiry (P06: keep the real cancellable handle) | dispatch throwing synchronously → `completeExecution` with a warm error result (slot not acquired on that path) |
| **Waiter subscription** (reactive subscriber + timer) | `ReactiveInvocationCoordinator.invoke` (`Mono.fromFuture(...).timeout(...)`) | waiter timeout, client cancel, or shared terminal completes the future | timeout/cancel releases only that waiter's subscription/timer; the shared future and record are untouched (I1) |
| **Function capacity/generation state** (`FunctionCapacityState`, `ReplicaStatusSnapshot.Entry`) | function register (queue manager / sync queue / snapshot) | function removal, after its in-flight work drains | remove under load deactivates rather than deletes while slots are held; drain-then-delete (P09/P10) |
| **Deployment resources** (proxy, `FunctionState`, HttpServer, executor, HttpClient) | `ContainerLocalDeploymentProvider` at provision | `deprovision` (close proxy after all replica removals) + Spring context shutdown | adapter failure must not lose the proxy reference or provider state (R6 → P08: keep ownership until cleanup completes; attempt independent cleanups) |
| **Executor/thread resources** (scheduler executor, replica-refresh pool) | `Scheduler`/`SyncScheduler.start`, `ReplicaStatusSnapshot` refresh pool | `SmartLifecycle.stop` (`shutdownNow` + shutdown); context shutdown | a `start` that throws shuts down the executor and clears the reference before rethrowing |
| **Metrics / meters** (Micrometer meters per function) | function register (`Metrics.registerFunction`, queue managers) | `Metrics.removeFunction` / queue removal listeners | a removed function's late event must not re-register meters (R8 → P09); removed-name sets retire with the generation |

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
| §8 deployment row / I10 | a deprovision adapter throw loses the proxy reference and provider state (R6) | P08 |
| §8 transport row / I3 | administrative expiry releases bookkeeping but holds no handle to cancel the dispatch (work can outlive its released capacity) | P06 |

No new state is introduced by this ADR. Any future state addition must carry a defined transition
into and out of it, an owner, and an unchanged shared-result guarantee.
