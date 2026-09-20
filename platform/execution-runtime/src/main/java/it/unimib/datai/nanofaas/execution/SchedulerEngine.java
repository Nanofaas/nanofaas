package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The single scheduling loop. It owns the pending work, the active {@link SchedulingIndex} and
 * the wake sequence; the index owns nothing but ticket order. One worker thread runs
 * {@link #tick()} in a loop and parks on the engine's own monitor between passes, so every
 * index operation is serialized without the index knowing about threads at all.
 *
 * <h2>Lock order</h2>
 * The engine's gate is a <strong>leaf</strong>: no code path holds it while acquiring another
 * lock. Selection, claim, commit, insertion and removal happen under it; capacity acquisition,
 * record inspection, submit and every lifecycle notification happen outside it. That is what
 * makes a {@code record -> gate} order (a retry publishing into the engine after releasing the
 * execution record) safe: the reverse edge {@code gate -> record} does not exist, so no cycle
 * can form. Anything may call {@link #signal()}, {@link #enqueue} or {@link #remove} while
 * holding its own lock, for the same reason.
 *
 * <h2>One selection per pass</h2>
 * A pass reaps due queue deadlines, then makes at most one selection and carries it to a
 * decision, mirroring {@code SyncScheduler.tickOnceInternal}. A generation whose lease could not
 * be acquired is dropped from consideration until the next {@link #signal()} — the wake sequence
 * replaces the old schedulers' "drop the function from activeFunctions and wait to be
 * re-signalled", which the passive index contract deliberately leaves to the engine.
 */
public final class SchedulerEngine implements AutoCloseable, SchedulerControl {

    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);

    /** Safety bound for a park with nothing pending; work, capacity and removals wake earlier. */
    static final long EMPTY_QUEUE_AWAIT_MS = 500L;
    /** Safety bound for a park with work that no generation can currently take. */
    static final long CAPACITY_BLOCKED_AWAIT_MS = 50L;
    /** Deadlines reaped per pass, so a burst of expiries cannot starve dispatch. */
    private static final int MAX_EXPIRED_PER_PASS = 64;
    /**
     * Tickets one switch may rebuild into the candidate index. The rebuild runs under the gate,
     * which is what makes a switch atomic against every concurrent insertion and removal, so it
     * is also what the gate hold time is paid out of. A deeper queue is refused as
     * {@link SchedulerSwitchException.Reason#TEMPORARY_CAP} and becomes switchable again as it
     * drains. This is the clock-free half of the budget; {@link #SWITCH_BUDGET_MS} is the other.
     */
    static final int MAX_SWITCH_REBUILD_TICKETS = 10_000;
    /** Monotonic budget for one rebuild, re-checked before every insertion into the candidate. */
    static final long SWITCH_BUDGET_MS = 50L;

    private final PendingWorkStore store;
    private final StrategyRegistry strategies;
    private final EngineDispatch dispatch;
    private final EngineReadiness readiness;
    private final Clock clock;
    private final LongSupplier nanoTime;

    private final Object gate = new Object();

    /** Queue deadlines of the tickets that have one, soonest first. Guarded by {@link #gate}. */
    private final TreeSet<SchedulingTicket> deadlines = new TreeSet<>(
            Comparator.comparing(SchedulingTicket::queueDeadline)
                    .thenComparingLong(SchedulingTicket::sequence));
    /** Generations whose lease could not be acquired since the last wake. Guarded by {@link #gate}. */
    private final Set<FunctionGeneration> blocked = new HashSet<>();
    /**
     * Removals that arrived while their ticket was already submitting. {@link PendingWorkStore}
     * deliberately gives no handle on a committed dispatch, so the request is held here and
     * applied if — and only if — that dispatch comes back for a requeue. Guarded by {@link #gate}.
     */
    private final Set<TicketId> cancelRequests = new HashSet<>();

    /**
     * The active policy and its index, published as one immutable pair. Written only under
     * {@link #gate}; volatile so {@link #snapshot()} can read a consistent pair without taking
     * it. The epoch is what a provisional claim carries, so a selection that spans a switch is
     * detected by value rather than by index identity.
     */
    private volatile ActiveScheduler active;
    private long wakeSequence;
    private boolean running;
    private Thread worker;

    public SchedulerEngine(PendingWorkStore store, StrategyRegistry strategies, String initialStrategy,
                           EngineDispatch dispatch, EngineReadiness readiness,
                           Clock clock, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.strategies = Objects.requireNonNull(strategies, "strategies must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.readiness = Objects.requireNonNull(readiness, "readiness must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        Objects.requireNonNull(initialStrategy, "initialStrategy must not be null");
        SchedulingStrategy initial = strategies.require(initialStrategy);
        this.active = new ActiveScheduler(initial.id(), initial.newIndex(), 0L);
    }

    @Override
    public SchedulerSelection snapshot() {
        // The selection is an API override that does not outlive the process: on restart the
        // configured initial strategy wins again.
        return new SchedulerSelection(active.id(), strategies.ids(), "restart");
    }

    /**
     * Replaces the active index with a fresh one built by {@code strategy}, without draining
     * anything. Pending work is rebuilt into the candidate in sequence order; a selection the
     * loop is still carrying is provisional and returns itself to whichever index is active when
     * it lands; an attempt already committed to dispatch keeps its attempt, deadline and
     * sequence and simply requeues into the new index if it comes back.
     *
     * <p>The whole switch is one gate section. That is what makes "before or after" the only two
     * possible positions for a concurrent {@link #enqueue} or {@link #remove}: neither can
     * interleave with the rebuild, so no transition event buffer is needed and no admitted work
     * can fall between the two indexes. It also means new provisional claims cannot be taken
     * while the candidate is being built — {@link #selectAndClaim} runs under the same gate.
     *
     * @throws IllegalArgumentException  when no such strategy was built into this artifact
     * @throws SchedulerSwitchException  when the switch was refused before its commit point; the
     *                                   previous strategy is still active and untouched
     */
    @Override
    public void switchTo(String strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        // Validated before the gate is even taken, let alone the old index touched.
        SchedulingStrategy target = strategies.require(strategy);
        synchronized (gate) {
            ActiveScheduler current = active;
            if (current.id().equals(target.id())) {
                // Same strategy: a no-op for indexes and for the worker, by contract.
                return;
            }
            SchedulingIndex superseded;
            try {
                SchedulingIndex candidate = prepare(target);
                // Linearization point. Every validation is behind us and nothing below can fail
                // in a way that would have to undo this: discarding the old index is isolated
                // cleanup, not part of the transaction.
                active = new ActiveScheduler(target.id(), candidate, current.epoch() + 1);
                superseded = current.index();
            } finally {
                // Whether committed or refused, the selector re-examines everything: a generation
                // blocked against the old index must not stay blocked against an index that has
                // never been consulted for it.
                wake();
            }
            discard(superseded);
        }
        log.info("Scheduler strategy switched to {}", target.id());
    }

    /**
     * Under the gate. Builds the candidate index from the pending work, in sequence order, and
     * returns it without ever consulting it for dispatch. Claimed and submitting tickets are
     * deliberately absent: {@link PendingWorkStore#snapshotPending()} excludes them because
     * their attempt is already in flight and will put itself back into whichever index is
     * active when it lands. Any failure leaves the candidate cleared and the active index
     * untouched.
     */
    private SchedulingIndex prepare(SchedulingStrategy target) {
        List<PendingEntry> pending = store.snapshotPending();
        SchedulingIndex candidate;
        try {
            candidate = target.newIndex();
        } catch (RuntimeException failure) {
            throw new SchedulerSwitchException(SchedulerSwitchException.Reason.PREPARATION,
                    "Scheduler preparation failed", failure);
        }
        long deadlineNanos = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(SWITCH_BUDGET_MS);
        int rebuilt = 0;
        try {
            for (PendingEntry entry : pending) {
                if (rebuilt == MAX_SWITCH_REBUILD_TICKETS) {
                    throw new SchedulerSwitchException(SchedulerSwitchException.Reason.TEMPORARY_CAP,
                            "Too much pending work to switch scheduler: " + pending.size()
                                    + " tickets, cap " + MAX_SWITCH_REBUILD_TICKETS);
                }
                if (nanoTime.getAsLong() - deadlineNanos >= 0) {
                    throw new SchedulerSwitchException(SchedulerSwitchException.Reason.TIMEOUT,
                            "Scheduler preparation outran its " + SWITCH_BUDGET_MS + " ms budget after "
                                    + rebuilt + " of " + pending.size() + " tickets");
                }
                candidate.add(entry.ticket());
                rebuilt++;
            }
        } catch (RuntimeException failure) {
            try {
                candidate.clear();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof SchedulerSwitchException refused) {
                throw refused;
            }
            throw new SchedulerSwitchException(SchedulerSwitchException.Reason.PREPARATION,
                    "Scheduler preparation failed", failure);
        }
        return candidate;
    }

    /**
     * Under the gate, after the commit. The superseded index is unreachable by then — every
     * path reads {@code active.index()} — so this is bookkeeping for the index's own sake and
     * a throw from it must not be reported as a failed switch.
     */
    private void discard(SchedulingIndex superseded) {
        try {
            superseded.clear();
        } catch (RuntimeException failure) {
            log.warn("Superseded scheduling index failed to clear", failure);
        }
    }

    /**
     * Admits work: one reservation in the store and one ticket in the active index, or nothing
     * at all when the store is full. Safe to call from any thread, including one holding an
     * execution record's monitor.
     */
    public boolean enqueue(PendingEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        synchronized (gate) {
            if (!store.offer(entry)) {
                return false;
            }
            active.index().add(entry.ticket());
            track(entry.ticket());
            wake();
        }
        return true;
    }

    /**
     * Withdraws admitted work. A pending or claimed ticket leaves both the store and the index
     * and is reported through {@link EngineDispatch#removed}; a ticket whose dispatch is already
     * submitting cannot be withdrawn here (the attempt is committed and belongs to the
     * lifecycle), so the request is remembered and applied if input backpressure hands that
     * ticket back for a requeue.
     */
    public void remove(TicketId id) {
        Objects.requireNonNull(id, "id must not be null");
        PendingEntry removed;
        synchronized (gate) {
            removed = store.remove(id);
            if (removed != null) {
                retire(removed.ticket());
            } else if (store.get(id) != null) {
                cancelRequests.add(id);
            }
        }
        if (removed != null) {
            dispatch.removed(removed.task());
        }
    }

    /**
     * Advances the wake sequence: new work, released capacity, a capacity change or a shutdown.
     * Clears the blocked generations so the next pass reconsiders them.
     */
    public void signal() {
        synchronized (gate) {
            wake();
        }
    }

    /** One bounded pass: reap due deadlines, then carry at most one selection to a decision. */
    public void tick() {
        pass();
    }

    /** Starts the worker thread. Idempotent. */
    public void start() {
        Thread started;
        synchronized (gate) {
            if (running) {
                return;
            }
            running = true;
            worker = new Thread(this::loop, "nanofaas-scheduler-engine");
            worker.setDaemon(true);
            started = worker;
        }
        try {
            started.start();
        } catch (RuntimeException | Error failure) {
            // A worker that never started must not leave the engine claiming to be running:
            // a later start() has to be able to try again.
            synchronized (gate) {
                running = false;
                worker = null;
            }
            throw failure;
        }
    }

    /** Stops the worker thread and waits briefly for it to leave the loop. Idempotent. */
    @Override
    public void close() {
        Thread toStop;
        synchronized (gate) {
            if (!running && worker == null) {
                return;
            }
            running = false;
            toStop = worker;
            worker = null;
            wake();
        }
        if (toStop == null) {
            return;
        }
        toStop.interrupt();
        try {
            toStop.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        try {
            while (isRunning()) {
                try {
                    runOnce();
                } catch (RuntimeException failure) {
                    // Lifecycle code is pluggable and this is the only scheduling thread: one bad
                    // callout must not take it down and leave every queued invocation stalled.
                    // Mirrors async-queue Scheduler.loop. An Error is deliberately NOT caught —
                    // the JVM is compromised at that point and spinning on is worse than stopping
                    // — but the finally below still leaves the engine restartable.
                    log.error("Error in scheduler engine loop", failure);
                    // Park on the same notifiable safety bound before retrying: a callout that
                    // throws on every pass would otherwise burn a core. The predecessor could not
                    // spin here because its loop blocked on a 500 ms queue poll.
                    long observed;
                    synchronized (gate) {
                        observed = wakeSequence;
                    }
                    await(CAPACITY_BLOCKED_AWAIT_MS, observed);
                }
            }
        } finally {
            retireWorker();
        }
    }

    /**
     * One loop iteration: a pass plus the park that follows it. Package-private so a test can
     * exercise the park's safety bound without starting a thread.
     */
    void runOnce() {
        long observed;
        synchronized (gate) {
            // Read BEFORE the pass: anything that fires during it advances the sequence and
            // the park below returns at once instead of sleeping through the change.
            observed = wakeSequence;
        }
        long budgetMs = pass();
        if (budgetMs > 0 && !await(budgetMs, observed)) {
            // The safety bound elapsed with no notification at all. Re-examine every generation
            // from scratch on the next pass, the way the predecessors' scans re-evaluated
            // hasAvailableSlot every time: a capacity release that arrives on a path which never
            // signals must not park a function forever (SyncScheduler CAPACITY_BLOCKED_AWAIT_MS
            // exists for exactly this).
            synchronized (gate) {
                blocked.clear();
            }
        }
    }

    /**
     * Whatever ends the worker — a normal stop, an interrupt, or an Error this loop deliberately
     * does not swallow — must leave the engine restartable instead of claiming to be running
     * with a dead thread, which would make {@link #start()} a permanent no-op.
     */
    @SuppressWarnings("ReferenceEquality") // Thread identity: only THIS worker may retire itself.
    private void retireWorker() {
        synchronized (gate) {
            if (worker == Thread.currentThread()) {
                worker = null;
                running = false;
            }
        }
    }

    private boolean isRunning() {
        synchronized (gate) {
            return running && !Thread.currentThread().isInterrupted();
        }
    }

    /**
     * @return true only when the wake sequence actually advanced — something signalled. False
     *         when the safety bound elapsed, when the thread was interrupted, or when there was
     *         nothing to park on: in all three the caller must re-examine capacity from scratch
     *         rather than trust the previous pass's blocked set.
     */
    private boolean await(long budgetMs, long observed) {
        long deadlineNanos = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        synchronized (gate) {
            while (running && wakeSequence == observed) {
                long remainingNanos = deadlineNanos - nanoTime.getAsLong();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    gate.wait(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return wakeSequence != observed;
        }
    }

    /**
     * @return how long the worker may park afterwards, in milliseconds; 0 when the pass did work
     *         and should be followed immediately by another.
     */
    private long pass() {
        Instant now = clock.instant();
        List<PendingEntry> expired;
        Claim claim;
        synchronized (gate) {
            expired = reapExpired(now);
            claim = selectAndClaim(now);
        }
        // Outside the gate: lifecycle notifications never run under it.
        for (PendingEntry entry : expired) {
            dispatch.expired(entry.task());
        }
        if (claim == null) {
            return expired.isEmpty() ? idleBudgetMs() : 0L;
        }
        return carry(claim);
    }

    private long idleBudgetMs() {
        synchronized (gate) {
            return store.pendingCount() == 0 ? EMPTY_QUEUE_AWAIT_MS : CAPACITY_BLOCKED_AWAIT_MS;
        }
    }

    /** Under the gate. Only tickets in the index are claimed, and the index holds no submitting ticket. */
    private Claim selectAndClaim(Instant now) {
        Predicate<FunctionGeneration> runnable =
                generation -> !blocked.contains(generation) && readiness.runnable(generation);
        ActiveScheduler scheduler = active;
        SchedulingTicket ticket = scheduler.index().select(now, runnable);
        if (ticket == null) {
            return null;
        }
        PendingEntry entry = store.claim(ticket.id());
        if (entry == null) {
            // The index outlived its entry: drop the orphan rather than re-selecting it forever.
            log.warn("Dropping indexed ticket with no pending entry: {}", ticket.id());
            scheduler.index().remove(ticket.id());
            if (ticket.queueDeadline() != null) {
                deadlines.remove(ticket);
            }
            return null;
        }
        return new Claim(ticket, scheduler.epoch());
    }

    /** Outside the gate, except where noted: record check, lease acquisition, commit, submit. */
    private long carry(Claim claim) {
        SchedulingTicket ticket = claim.ticket();
        // A claim is provisional and lives only inside this method. isCurrent, tryAcquire and
        // release are all pluggable lifecycle code: if one of them throws, the loop's barrier
        // catches it, and without this guard the claim would stay in the store forever —
        // reserved, unselectable and invisible to the deadline reap.
        boolean claimSettled = false;
        try {
            if (!dispatch.isCurrent(ticket)) {
                PendingEntry dropped;
                synchronized (gate) {
                    dropped = store.remove(ticket.id());
                    if (dropped != null) {
                        retire(ticket);
                    }
                }
                claimSettled = true;
                if (dropped != null) {
                    dispatch.removed(dropped.task());
                }
                return 0L;
            }
            DispatchOwnership lease = dispatch.tryAcquire(ticket);
            if (lease == null) {
                synchronized (gate) {
                    store.abort(ticket.id());
                    active.index().defer(ticket.id());
                    blocked.add(ticket.generation());
                }
                claimSettled = true;
                return CAPACITY_BLOCKED_AWAIT_MS;
            }

            InvocationTask task;
            synchronized (gate) {
                PendingEntry current = store.get(ticket.id());
                if (current == null) {
                    // The claim was removed while the lease was being acquired: nothing to dispatch.
                    task = null;
                } else if (claim.epoch() != active.epoch()) {
                    // The index was swapped under this selection. A provisional claim is not a
                    // committed dispatch, so this attempt goes back to the index that is active now
                    // and gets re-selected by the new policy. The rebuild excluded it — it was
                    // claimed — so this add cannot collide with a copy of itself.
                    store.abort(ticket.id());
                    active.index().add(ticket);
                    task = null;
                } else {
                    store.commit(ticket.id());
                    active.index().remove(ticket.id());
                    task = current.task();
                }
            }
            // Past this point the claim is settled either way: aborted above, or committed — and
            // a committed dispatch's reservation belongs to submit()'s own finally.
            claimSettled = true;
            if (task == null) {
                lease.release();
                return 0L;
            }
            submit(ticket, task.withDispatchLease(lease), lease);
            return 0L;
        } finally {
            if (!claimSettled) {
                synchronized (gate) {
                    store.abort(ticket.id());
                    // defer of an id the (possibly newly built) index does not hold is a no-op
                    // in both policies; the ticket returns through the index-swap branch above.
                    active.index().defer(ticket.id());
                }
            }
        }
    }

    private void submit(SchedulingTicket ticket, InvocationTask leased, DispatchOwnership lease) {
        SchedulerDispatchSupport.Result result = null;
        try {
            result = SchedulerDispatchSupport.dispatchWithFailureCleanup(
                    leased,
                    () -> dispatch.submit(leased),
                    lease::release,
                    failure -> dispatch.rejected(leased, failure),
                    log);
        } finally {
            // Both predecessors settled the reservation in a finally (SyncScheduler,
            // Scheduler), and for good reason: dispatchWithFailureCleanup can itself throw — a
            // throwing rejected() escapes its FAILED branch, a throwing lease.release() escapes
            // the backpressure branch. A ticket left in `submitting` holds its reservation
            // forever: no index holds it so nothing can select it, and the deadline reap cannot
            // remove a submitting ticket either. When the settlement is unknown the reservation
            // is released, as the predecessors did; concluding that execution is the lifecycle's
            // job, not the queue's.
            if (result == SchedulerDispatchSupport.Result.INPUT_BACKPRESSURED) {
                requeue(ticket);
            } else {
                finishSubmit(ticket);
            }
        }
    }

    /**
     * Input backpressure: the reservation was held for the whole submit and is kept, and the
     * ticket goes back — same sequence, same deadlines — into whichever index is active now.
     * A removal that arrived mid-submit is applied here instead.
     */
    private void requeue(SchedulingTicket ticket) {
        PendingEntry cancelled = null;
        synchronized (gate) {
            store.requeueSubmit(ticket.id());
            if (cancelRequests.remove(ticket.id())) {
                cancelled = store.remove(ticket.id());
                if (ticket.queueDeadline() != null) {
                    deadlines.remove(ticket);
                }
            } else {
                active.index().add(ticket);
                // Re-track in case a reap polled this deadline off while the submit was in
                // flight and then found the ticket committed; TreeSet.add is idempotent here.
                track(ticket);
                wake();
            }
        }
        if (cancelled != null) {
            dispatch.removed(cancelled.task());
        }
    }

    private void finishSubmit(SchedulingTicket ticket) {
        synchronized (gate) {
            store.finishSubmit(ticket.id());
            cancelRequests.remove(ticket.id());
            if (ticket.queueDeadline() != null) {
                deadlines.remove(ticket);
            }
        }
    }

    /** Under the gate. Drops every pending ticket already past its queue deadline, bounded. */
    private List<PendingEntry> reapExpired(Instant now) {
        List<PendingEntry> expired = new ArrayList<>();
        while (expired.size() < MAX_EXPIRED_PER_PASS && !deadlines.isEmpty()) {
            SchedulingTicket head = deadlines.first();
            if (head.queueDeadline().isAfter(now)) {
                break;
            }
            deadlines.pollFirst();
            PendingEntry entry = store.remove(head.id());
            if (entry != null) {
                // Out-of-band removal: the per-function index treats this exactly as it treats a
                // dispatch of that function's turn (see remove()).
                active.index().remove(head.id());
                expired.add(entry);
            }
            // A null entry is a ticket already gone or already submitting: its dispatch is
            // committed and no longer the queue's to expire.
        }
        return expired;
    }

    /**
     * Under the gate.
     *
     * <p><strong>Precondition for any future backoff policy.</strong> The engine holds no delayed
     * index: a ticket whose {@code notBefore} is in the future simply sits in the active index
     * until a scan finds it due. That is sound only because <em>nothing is ever future-dated in
     * any current profile</em> — there is no backoff, so a retry is published with
     * {@code notBefore = enqueuedAt}. It is NOT true that both indexes tolerate future-dated
     * tickets: {@code SharedQueueSchedulingStrategy.select} scans its window and skips forward,
     * but {@code PerFunctionSchedulingStrategy.select} inspects only {@code fifo.peekFirst()}
     * before moving to the next function, so a future-dated head hides every later ticket of the
     * same function until it comes due. Anyone introducing a backoff that sets
     * {@code notBefore > enqueuedAt} must therefore either add a bounded delayed index here (and
     * wake on the nearest {@code notBefore}, not only on the safety bound) or change the
     * per-function index to scan past a not-yet-due head. Do not assume the current arrangement
     * survives that change.
     */
    private void track(SchedulingTicket ticket) {
        if (ticket.queueDeadline() != null) {
            deadlines.add(ticket);
        }
    }

    /**
     * Under the gate. Takes a ticket out of the active index and the deadline set.
     *
     * <p>Out-of-band removal has no equivalent in the schedulers this engine replaces — they
     * could not remove from the middle of a queue at all — and
     * {@code PerFunctionSchedulingStrategy.remove} advances the round-robin turn as if the
     * ticket had been dispatched. That is accepted deliberately: leaving a ticket in the index
     * after its entry is gone would corrupt selection (it would be picked, fail to claim, and
     * never leave). Two cases, both accepted:
     *
     * <ul>
     *   <li><strong>Same function, head ticket</strong> (the common one: expiry of the oldest
     *       ticket). The turn advance is exactly what dispatching it would have done.</li>
     *   <li><strong>A different function than the one mid-turn.</strong> Retiring a ticket of
     *       function B while A is mid-turn sets {@code turnFunction = B, turnCount = 1} and
     *       discards A's batch progress, so an expiry on one function perturbs another
     *       function's round-robin turn. No work is lost and no ticket is starved — only the
     *       batch boundary moves. Adding a {@code withdraw()} to {@link SchedulingIndex} would
     *       fix it cleanly, but that contract is consumed by both strategies and by the switch
     *       and fairness work, so it is not widened for a defect that loses nothing. Task 12's
     *       fairness tests measure this divergence; if it proves material there, {@code
     *       withdraw()} gets added then, on evidence.</li>
     * </ul>
     */
    private void retire(SchedulingTicket ticket) {
        active.index().remove(ticket.id());
        if (ticket.queueDeadline() != null) {
            deadlines.remove(ticket);
        }
    }

    /** Under the gate. */
    private void wake() {
        wakeSequence++;
        blocked.clear();
        gate.notifyAll();
    }

    /** One selection in flight, with the epoch of the index it was selected from. */
    private record Claim(SchedulingTicket ticket, long epoch) {
    }

    /**
     * The active policy, its index and the epoch that dates it. Replaced wholesale by a switch;
     * never mutated.
     */
    private record ActiveScheduler(String id, SchedulingIndex index, long epoch) {
    }
}
