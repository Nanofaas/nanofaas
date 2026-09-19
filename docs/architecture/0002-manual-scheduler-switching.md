# ADR 0002 — Manual scheduler switching: baseline contract and lock order

- **Status:** Accepted (normative contract for the manual-scheduler-switching campaign; Task 0
  freezes it, Task 13 closes it)
- **Date:** 2026-09-19
- **Campaign:** manual scheduler switching (issue #208), branch `feat/208-manual-scheduler-switching`
- **Reviewed against:** `05f49dcb7b06f18682fb861e0688f7202d3fb8df` (base commit; working tree clean)
- **Depends on:** [ADR 0001 — Execution binding lifecycle contract](0001-execution-lifecycle-contract.md),
  whose invariants (I1–I10), transition table (§6) and resource ownership matrix (§8) this ADR does
  not change and must remain consistent with
- **Related:** `docs/experiments/scheduler-switching-2026-09/BASELINE.md`, `STATO.md`,
  `budgets.json` (same directory)

## 1. Context and problem

Today two mutually exclusive optional modules each own their own scheduling loop and thread:
`async-queue`'s `Scheduler` (per-function queues, round-robin batching,
`platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java`)
and `sync-queue`'s `SyncScheduler` (a single global depth-bounded queue with a wait estimate,
`platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/scheduler/SyncScheduler.java`).
Their `module.properties` declare `conflicts=` each other, so they cannot be selected together;
which one runs is a **build-time** choice today.

This campaign's goal is to make the *scheduling strategy* a **startup-selectable, then
hot-switchable** choice behind one engine, without changing the execution lifecycle contract ADR
0001 already froze. This ADR is that contract, written before any production code changes, exactly
as ADR 0001 was written before P02–P09. It records:

- the mapping between today's three test profiles and the strategies they exercise (§2);
- the lock order the future engine's "index gate" must hold to (§3), stated so it can be checked
  by inspection at every later task;
- the one concurrency hazard already present in today's code that motivates that lock order (§4)
  and the task (Task 4) that closes it;
- what is explicitly out of scope for this ADR (§5).

As first written, this ADR changes no production code (`src/main` is untouched by Task 0).

## 2. Baseline profile mapping

The three profiles in `docs/experiments/scheduler-switching-2026-09/BASELINE.md` map onto the
scheduling strategies this campaign will unify:

| Profile | Modules | Scheduling strategy exercised |
|---|---|---|
| 1 | `async-queue`, `runtime-config` | Per-function queues + `Scheduler`'s round-robin batching (`FUNCTION_QUEUE` admission via `ReactiveInvocationCoordinator`, ADR 0001 §11.1) |
| 2 | `sync-queue`, `runtime-config` | Global depth-bounded queue + `SyncScheduler`'s wait-estimate admission (`SyncQueueGateway`, ADR 0001 §11.1) |
| 3 | `runtime-config` only | No queue module: core direct/no-async admission (`InvocationEnqueuer` core default, no scheduler thread at all) |

Profile 3 is not "a third strategy" in the same sense as 1 and 2 — it is the no-scheduler control:
core direct admission has no queue, no scheduler thread, and no backlog to switch. It stays in the
baseline because later tasks must show that selecting a strategy at startup does not regress the
no-queue path, and because it is the only profile where neither `conflicts=` module is loaded.

Per this task's brief and CLAUDE.md's project constraints, the two queue modules are never run
together: their `module.properties` mutual `conflicts=` entries mean a single classpath cannot
carry both today, so profiles 1 and 2 were run in **separate** Gradle invocations, never combined.

## 3. Lock order: the index gate, the record monitor, and `DispatchCapacity`

The plan's later tasks (Task 4 onward) introduce a single bounded `SchedulerEngine` loop with one
"index gate" — the mutex/monitor protecting the pending-work index's claim/abort/commit state
(`PendingWorkStore`, `SchedulingTicket` claim lifecycle). This ADR fixes the lock order that gate
must hold to, so it composes with ADR 0001's execution lifecycle without introducing a new way to
deadlock or to violate I1–I4:

1. **The index gate never acquires a monitor on `ExecutionRecord`.** Index selection, claim, abort
   and commit operate only on the engine's own pending-work index and ticket state. They observe
   generation/readiness (`EngineReadiness.runnable`, a local, non-blocking read) but never reach
   into the record ADR 0001 §8 already assigns to `ExecutionStore`/`ExecutionLifecycle`.
2. **The index gate never calls `DispatchCapacity`.** Lease acquisition
   (`FunctionCapacityRegistry`/`DispatchCapacity.tryAcquireLease`) happens **outside** the gate, per
   the three-phase pattern Task 4 specifies: local observations outside the gate → select/claim
   under the gate → lease acquisition and record/generation revalidation outside the gate →
   claim/epoch re-validation under the gate → commit → submit outside the gate. A lease is never
   acquired while the gate is held, and the gate is never re-entered while a lease acquisition is
   in flight.
3. **The index gate never waits for completion.** It does not block on the shared completion
   future, on a dispatch/transport handle, or on any I/O. `submit` is documented as non-blocking
   (Task 4's `EngineDispatch.submit` contract); a ticket whose dispatch fails synchronously returns
   the lease and requeues, all outside the gate.

**Why this order, not the reverse.** ADR 0001 already establishes that the execution record's
terminal transition (`settle`, §6) and its resource release (§8) are owned by
`ExecutionCompletionHandler`/`ExecutionStore`, and that a retry's re-admission
(`InvocationEnqueueSupport.enqueueOrThrow`) is triggered from **inside** that machinery today (§4
below). If the index gate were allowed to acquire the record monitor, or to call into
`DispatchCapacity` while held, two independent lock-acquisition paths — dispatch (gate → lease →
record) and completion/retry (record → gate, today) — could form a cycle. Fixing the order at the
gate side only defers the actual hazard; §4 records why the record side must also change, and
names the task that changes it.

## 4. The concurrency hazard this ADR requires closing (Task 4)

**Today**, `ExecutionCompletionHandler`'s retry path can re-enter admission **while the execution
record's own monitor is held**: a retryable failure is handled under the record's lock
(`resetForRetry`, ADR 0001 §6 row 5), and the retry's re-admission call
(`InvocationEnqueueSupport.enqueueOrThrow`) happens synchronously from that same call chain. This
is safe today only because there is no separate "index gate" to conflict with — each queue module
owns its own lock-free/short-critical-section structures. It stops being safe once a single engine
introduces the index gate described in §3, because a synchronous retry-publish still holding the
record monitor would then need the engine to accept work while a second path (dispatch) reaches
the record through the gate → lease → record direction. That is the lock-order cycle this ADR
forbids in §3, and it cannot be forbidden by fiat on the gate side alone if the record side keeps
calling into admission while held.

**The fix, scheduled for Task 4** (not this task): move the retry's `enqueueOrThrow` call **out of**
the record's monitor. Task 4's brief states the target sequence precisely: prepare the new attempt
under the monitor, publish it after the monitor is released, then revalidate attempt/generation and
conclude the same execution if the publish fails — without introducing a second user-facing
admission. This ADR records the *reason* the extraction is required (the lock-order rule in §3
cannot otherwise hold); Task 4 owns the *implementation* and its own regression tests
(`InvocationServiceCoreRetryTest`, `InvocationServiceRetryQueueFullTest`).

Until Task 4 lands, the hazard is latent, not active: no index gate exists yet in the current
codebase, so there is no second lock for the record-held retry call to conflict with. This ADR
documents the hazard now, before the gate is introduced, precisely so Task 4 is not a surprise fix
but a scheduled closure of a defect this contract already names.

## 5. What this ADR does not decide

- It does not specify `SchedulingStrategy`, `SchedulerEngine`, `PendingWorkStore` or any other
  Task-1-through-13 interface; those are each task's own contract, reviewed against this ADR's lock
  order as they land.
- It does not change, relax, or reinterpret any invariant, transition, or ownership row in ADR
  0001. Where a later task's design would require such a change, that change belongs in an
  amendment to ADR 0001, not here.
- It does not claim the P24/P25 lifecycle-memory campaign measured itself closed. See
  `docs/experiments/scheduler-switching-2026-09/STATO.md` for the operator's 2026-09-19 closure
  declaration and the attribution limit it leaves for this campaign's own memory/latency
  measurements.
- It records no performance numbers. `docs/experiments/scheduler-switching-2026-09/budgets.json`
  freezes the thresholds later tasks measure against; this task ran none of those measurements.
