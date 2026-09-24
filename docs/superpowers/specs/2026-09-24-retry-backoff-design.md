# Retry backoff and upstream Retry-After

Date: 2026-09-24
Status: proposed design, ready for review; implementation and live validation pending.
Baseline: `feat/208-manual-scheduler-switching`, local HEAD `a17289c2` plus the existing working-tree changes.

## 1. Problem and evidence

Retries currently consume their budget immediately when a transient failure lasts longer than
one dispatch round trip. The purpose of this change is to space those attempts, honor upstream
admission backpressure, and preserve the scheduler's bounded memory and execution lifecycle.

The investigation supplied with this request reports a post-deploy burst of 300 invocations,
403 dispatches, and 103 retries. Eighteen invocations exhausted their four attempts during the
same short saturation window. The reported Java runtime response was HTTP 429,
`RUNTIME_CALLBACK_SATURATED`, with `Retry-After: 1`. The earlier image also exhibited failures
(29 and then 4), followed by a successful 300/300 run. These are reported measurements, not a
benchmark reproduced while writing this document; the raw logs are not attached to this spec.
They support investigating retry timing rather than attributing the incident to dependency updates.

Two triggers must be distinguished:

- Runtime callback admission can saturate even when the pod is ready. Readiness is not a promise
  that every burst can be admitted. Increasing callback workers changes capacity, not retry semantics.
- A newly created Service may have no ready endpoints yet. The current wake-up gate is scoped to
  eligible scale-from-zero deployments and does not generally wait for every initial deployment.
  Backoff can bridge a short unavailable period; a deployment taking longer than the retry budget
  can still fail. General readiness gating is a separate change.

## 2. Verified current behavior

The following was checked in the current source:

| Component | Current behavior relevant to the change |
| --- | --- |
| `ExternalDispatcher` | Unmarked non-2xx responses become `EXTERNAL_ERROR`; upstream retry timing is discarded. Valid `X-NanoFaaS-Function-Status` responses are successful function envelopes even for an application 429 or 500. |
| `DispatchResult` | Carries the invocation result and cold-start information, with no retry timing metadata. |
| `AttemptCoordinator` | Retries unsuccessful results while `attempt <= maxRetries`; prepares under the execution-record monitor and publishes after releasing it. |
| `RetryScheduler` | Accepts only `enqueue(InvocationTask)`; has no delay argument. |
| `EngineInvocationEnqueuer` | Builds immediately eligible tickets for function queues; delegates sync retries to `EngineSyncQueueGateway`. |
| `EngineSyncQueueGateway` | Applies sync admission and a queue-wait deadline beginning at queue admission. |
| `SchedulerEngine.track` | Explicitly warns that future `notBefore` values require delayed-ticket handling and a corresponding wake-up. |
| `PerFunctionSchedulingStrategy` | A future-dated FIFO head can hide later runnable work for the same function. |
| Direct admission profile | Uses `ExecutorBackedInvocationEnqueuer` for bounded internal retries, independently of public async admission. |

The runtime saturation contract is in [the SDK contract](../../../sdks/runtime-contract/README.md).
The execution deadline and ownership rules are in
[ADR 0001](../../architecture/0001-execution-lifecycle-contract.md); scheduling and lock ordering
are in [ADR 0002](../../architecture/0002-manual-scheduler-switching.md).

## 3. Scope and approach

The proposed solution has three parts: carry retry timing out of the HTTP dispatcher, calculate
backoff once in the attempt coordinator, and schedule delayed attempts without occupying a
dispatch slot. It covers function-queue, sync-queue, and direct admission profiles.

| Option | Tradeoff | Decision |
| --- | --- | --- |
| Engine-owned delayed index | Keeps future tickets out of strategy selection; one wake-up mechanism; requires lifecycle and switch integration. | Recommended. |
| Scan past delayed tickets inside each strategy | Requires changes in every strategy, including bounded-window behavior, and still needs timed wake-ups. | Rejected for this change. |
| Sleep in dispatch/retry workers or increase SDK callback workers | Holds resources during waits or moves the saturation threshold; does not provide scheduler-wide timing semantics. | Rejected. |

This specification does not introduce a new error retryability taxonomy. Existing unsuccessful
outcomes remain eligible according to the current attempt budget. This avoids silently changing
LOCAL handler errors, callbacks, or legacy external endpoint behavior while fixing timing.
Offloaded invocations retain their existing terminal policy, without local redispatch.

General initial-deployment readiness gating and changing SDK worker defaults are independent work.
The public function schema and default `maxRetries: 3` remain compatible: one initial attempt
and at most three additional attempts. `maxRetries: 0` means no retry.

## 4. Retry timing policy

Introduce startup configuration under `nanofaas.retry`:

| Property | Proposed default | Validation |
| --- | --- | --- |
| `initial-backoff` | `100ms` | Positive finite duration. |
| `max-backoff` | `2s` | Finite, at least `initial-backoff`. |

These defaults are design choices to validate with the incident workload, not measured optimal
values. They apply to every currently retryable unsuccessful attempt. No dynamic runtime-config
API or per-function backoff fields are required.

For retry ordinal `r` (1 is the retry after failed attempt 1):

```text
base = min(maxBackoff, initialBackoff * 2^(r - 1))
delay = uniform(base / 2, base)
notBefore = max(failureObservedAt + delay, upstreamRetryAt when present)
```

Use overflow-safe, saturating arithmetic. Inject the clock and random source for deterministic
tests. Calculate the value once; publication delays, capacity contention, and strategy switches
must not draw new jitter or restart the delay. Default local delays are therefore 50–100ms,
100–200ms, and 200–400ms for three retries.

The configured cap applies to the locally generated backoff only. A valid upstream request to
wait longer must not be shortened to that cap. Retention is bounded by queue and execution
lifetimes, not by dispatching early against upstream backpressure.

## 5. HTTP metadata and compatibility

Extend the internal `DispatchResult` with optional `Instant retryNotBefore`. Keep its existing
three-argument construction and `warm(...)` factory as no-hint paths. This field belongs to the
transport result, not `InvocationResult`, the public error DTO, or an arbitrary response header map.

`ExternalDispatcher` extracts `Retry-After` for unmarked HTTP 429 and 503 responses:

- Nonnegative integer seconds become an absolute instant relative to response-header receipt.
- An HTTP date becomes an absolute instant; a past date contributes no extra delay.
- Missing, malformed, negative, or conflicting multiple values fall back to local backoff.
- Parse overflow safely. A syntactically valid but unrepresentably large delay saturates to the
  maximum representable instant; it must not wrap into an immediate retry.
- Zero does not disable the positive local backoff.

Capture the header before body decoding, and carry it through an error-body decoding failure
where possible. Never parse retry decisions from error-message text. A bounded diagnostic log
may retain upstream status; the public `EXTERNAL_ERROR` representation stays compatible.

The valid function-status marker takes precedence over HTTP retry interpretation. A handler's
intentional 429/503, including its `Retry-After` header, remains a successful function envelope
and is returned to its caller without platform retry. Transport connection failures and timeouts
have no upstream hint and use local backoff. Callback delivery retries remain SDK-owned and do
not advance the invocation attempt number.

## 6. Publication and deadlines

Change the retry port to require `enqueue(task, notBefore)` for delayed publication. A one-argument
convenience method may delegate to immediate scheduling, but no default implementation may
silently discard a supplied delay. Update the metered decorator, engine adapter, direct adapter,
test doubles, and sync gateway together. Public first admission remains immediate.

`AttemptCoordinator` computes timing for the failed attempt and prepares the retry under the
record monitor. Its pending-retry value carries the task and timing. Publication still happens
outside that monitor. A duplicate or stale completion cannot schedule a second retry or replace
the current attempt's hint. Preserve execution identity, generation, input ownership, and the
existing increment of `X-Dispatch-Attempt`.

`enqueuedAt` records actual retry admission; `notBefore` records eligibility. Do not overwrite the
invocation's original admission time or use a future enqueue timestamp to disguise backoff.

Deadline rules:

1. A caller's waiter timeout ends only that waiter's wait. It does not cancel a shared delayed
   retry. A short waiter can time out while a later poll observes eventual success.
2. The function's attempt timeout starts with actual dispatch; waiting for backoff does not spend
   the next attempt's service-time budget.
3. For sync-queue admission, the existing queue deadline starts when that retry is admitted.
   Backoff counts as queue waiting. If `notBefore >= queueDeadline`, retain the bounded ticket
   until queue expiry and conclude through the existing `QUEUE_TIMEOUT` path, without dispatch.
4. The execution's administrative maximum lifetime remains anchored to initial admission and
   is never renewed by retries. Its expiry concludes through `EXECUTION_EXPIRED` and removes
   delayed references. Function queues without a queue deadline still have this lifetime bound.
5. Never clamp `notBefore` down to a deadline. Expiry wins at equality; an attempt must not run
   early just because there is insufficient time left to honor the delay.

Queue admission refusal retains the current completion policy using the failed attempt's error.
Resource cleanup remains exactly once on false returns, publication exceptions, and terminal
races. Waiting retries hold bounded pending-work/input reservations, not a new dispatch lease.
A previous physical attempt may retain its own lease until it actually drains, as required today.

## 7. Engine delayed index

Add an engine-owned ordered set of future tickets, sorted by `(notBefore, sequence, ticketId)`.
Reuse the existing pending store and reservation caps; the delayed set stores ticket metadata
only and does not own payloads, futures, or another queue reservation.

Under the engine gate, every pending ticket is in exactly one eligibility location: the active
strategy index or the delayed set. A ticket with a queue deadline additionally belongs to the
existing deadline index. Claimed/submitting work follows the existing store state machine.

On enqueue or requeue, route future tickets to the delayed set and eligible tickets to the active
strategy. At each pass, reap expired tickets before promoting due delayed tickets. Promote at most
64 tickets per pass, then allow selection; immediately repeat when more due promotions remain.
Promotion preserves attempt identity, admission sequence, deadline, and reservation. It is not
another admission or retry and must not increment those counters.

The idle wait is bounded by the earliest queue deadline, earliest `notBefore`, and the existing
safety interval. Inserting an earlier delayed ticket signals the engine using the existing wake
sequence so a concurrent insertion cannot be lost between selection and parking. Use monotonic
time for elapsed parks and the injected clock for absolute ticket instants. Recheck absolute
eligibility after waking; clock changes must not create a busy loop or bypass `notBefore`.

Fresh runnable work must remain selectable behind a delayed retry of the same function under
both strategies. Ready work keeps the strategy's ordering contract; delayed work joins that
ordering when promoted, using its original sequence. No full-backlog scan is added to normal
dispatch or idle passes. Insert/remove/promotion cost is logarithmic in delayed-ticket count.

All retirement paths must remove the appropriate eligibility entry and deadline entry: explicit
removal, queue expiry, function removal/re-registration, administrative execution expiry, and
requeue after a failed claim. Preserve existing stop/restart semantics; restarting the worker
must re-evaluate retained future tickets. Disposal of an owner must release retained entries.
No record monitor, lifecycle callback, provider request, or lease acquisition runs under the gate.

### Strategy switches

The delayed set is engine-owned and survives a strategy switch. Build the candidate strategy
from pending tickets that are already in the ready location, not all `snapshotPending()` entries.
Membership is evaluated under the gate; elapsed time alone must not put a delayed ticket into
both indexes during a rebuild. After the switch, promotion feeds the newly active strategy.

Preserve bounded preparation, epoch validation, and rollback. A failed switch leaves both ready
and delayed membership intact. The existing 10,000-ticket safety cap must still account for total
pending work, including delayed entries; filtering must not permit an unbounded scan under the
gate. The delayed timing set is not a second strategy instance. Queue snapshots and cap checks
count delayed work once, alongside other reserved pending work.

## 8. Direct profile

The no-queue profile needs equivalent timing without enabling public queues or async submission.
Retain its bounded retry executor and add a single owned timer with an explicit finite admission
bound equal to the existing executor's configured outstanding-task capacity. Reserve that bound
before adding a timer task; the timer's internally unbounded queue is never the admission limit.

The bound covers waiting, due, and submitted retry jobs until dispatch takes ownership or the
job is discarded. Timer expiry submits to the existing executor without blocking the timer thread.
Acquire the dispatch lease only when the executor is ready to dispatch. Capacity refusal retains
the current direct-profile refusal behavior and does not restart a timer indefinitely.

Track jobs by attempt identity and generation. Terminal execution, function removal, shutdown,
timer rejection, and executor rejection must cancel/remove the job and release its reservation
and queued input exactly once. Remove canceled tasks from the timer queue. Revalidate terminal
state and generation before dispatch; a removed function cannot be resurrected by an old timer.

## 9. Metrics and diagnosis

Preserve existing metric meanings: `function_retry_total` records the current retry-decision
event (which precedes publication today), not an invented count of successful redispatches.
Do not increment it for promotions or capacity checks. Existing terminal metrics remain once
per invocation; the end-to-end timer includes backoff and queue waiting, while attempt service
time begins at dispatch. Delayed work remains visible in pending queue counts.

Add debug-level retry timing details to the scheduling decision: execution ID, failed attempt,
next attempt, selected `notBefore`, and whether an upstream hint contributed. Do not introduce
execution IDs or arbitrary runtime error strings as metric labels. No new metric family is
necessary to prove this change; deterministic dispatch timestamps and existing counters suffice.

## 10. Validation and acceptance

Use controlled clocks, randomness, transport responses, and barriers for unit and integration
tests. Real elapsed sleeps are not ordering evidence.

| Area | Required acceptance evidence |
| --- | --- |
| HTTP adapter | 429/503 hints survive; seconds/date/zero/past/malformed/overflow/multiple values are covered; function-marked 429/503 never retries. |
| Policy | Default ranges, cap, configurable durations, invalid configuration, no hint, and `maxRetries` 0/3; upstream hints cannot be shortened. |
| Coordinator | Exactly one retry for racing callback/HTTP completions; stale attempt hints ignored; publication outside record monitor; failed publication releases input. |
| Both strategies | A delayed head does not hide ready work for the same or another function; nothing dispatches before its due instant; bounded promotion makes progress. |
| Wake-up | A new earlier due time interrupts parking; no missed signal, polling dependency, or spinning with only delayed work. |
| Deadlines | Queue expiry wins at equality; max lifetime cancels delayed work; short waiter timeout leaves shared execution alive; attempt timeout starts at dispatch. |
| Lifecycle | Queue-full, cancellation, removal/re-registration, stop/restart, and physical-drain races leave no duplicate dispatch, reservation, or payload retention. |
| Switching | Successful and refused switches with mixed ready/delayed work, plus claim/requeue races, preserve every ticket exactly once. |
| Direct profile | Timing, bounded retention, dispatch-time lease acquisition, saturation, terminal removal, and shutdown work with queue modules absent. |

First reproduce the incident deterministically: an upstream returns 429 with `Retry-After: 1`
until a controlled release, then succeeds. With available queue/lifetime budget, there must be
no redispatch in the forbidden second and no failure caused solely by consuming all attempts
inside that window. Test connection refusal separately; a backend unavailable beyond the budget
must still terminate rather than retry forever.

For live validation, use NanoLab's existing Kubernetes environment and lifecycle scenario, with
300-invocation post-deploy bursts and a warm baseline. Record image digests, readiness/endpoint
observations, callback configuration, request timestamps, execution IDs, attempt timestamps,
HTTP outcomes, and before/after counter deltas. Use waiter budgets long enough to observe a
one-second upstream delay. Report initial-deployment unavailability separately from callback
saturation; do not claim that every cold deployment must achieve 300/300.

Run existing execution-runtime, control-plane retry, SDK-envelope, and both strategy suites.
Recheck the scheduler campaign's [performance budgets](../../experiments/scheduler-switching-2026-09/budgets.json):
five repetitions; at most 5% steady p99/throughput regression, 10% CPU-per-completion/post-GC heap
regression; switch pause p99 at most 100ms, maximum 250ms; preparation at most 2000ms; no more
than two live strategy indexes. Preserve the engine's stricter internal 50ms rebuild guard.
Exercise mixed delayed backlogs at 0/100/1,000/10,000 and the 1,000-switch soak. Compare useful
completed invocations rather than treating extra failed dispatches as throughput.

## 11. Implementation boundaries and review notes

Expected changes span `DispatchResult`, `ExternalDispatcher`, `AttemptCoordinator`, `RetryScheduler`,
its adapters/decorator, `EngineSyncQueueGateway`, `SchedulerEngine`, direct retry lifecycle wiring,
and their tests. Update `docs/control-plane.md`, ADR 0002's timing description, `application.yml`,
and Helm configuration documentation/exposure when implementing the startup properties. No public
OpenAPI schema change is proposed; describe the changed retry timing in relevant API prose.

GitNexus was queried for retry flows and `RetryScheduler` context before drafting. Its graph
identifies `AttemptCoordinator`, `MeteredRetryScheduler`, `EngineInvocationEnqueuer`, and
`ExecutorBackedInvocationEnqueuer` as consumers, and selection/notBefore and retry-publication
flows as relevant. The index is at `1284fec6`, 33 commits behind this checkout. An attempted
`analyze --index-only` exited unsuccessfully and reported truncated flow coverage. Impact lookup
for `SchedulerEngine` returned ambiguous candidates with maximum risk `CRITICAL` (457 impacted
symbols), while an exact class lookup returned `UNKNOWN`. The new document is also absent from
the index, as expected for a file that did not previously exist.

These results are unresolved impact evidence, not a clean regression check. Source inspection
confirms the design seams but cannot substitute for a current graph analysis before code edits.
Refresh the index, resolve the exact impacted symbols, and run complete graph change analysis
before an implementation commit. This document introduces no production-code changes and does
not claim that the reported incident has been fixed or independently reproduced.
