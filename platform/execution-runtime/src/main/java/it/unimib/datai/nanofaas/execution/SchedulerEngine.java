package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
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
public final class SchedulerEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);

    /** Safety bound for a park with nothing pending; work, capacity and removals wake earlier. */
    static final long EMPTY_QUEUE_AWAIT_MS = 500L;
    /** Safety bound for a park with work that no generation can currently take. */
    static final long CAPACITY_BLOCKED_AWAIT_MS = 50L;
    /** Deadlines reaped per pass, so a burst of expiries cannot starve dispatch. */
    private static final int MAX_EXPIRED_PER_PASS = 64;

    private final PendingWorkStore store;
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

    private SchedulingIndex activeIndex;
    private String activeStrategy;
    private long wakeSequence;
    private boolean running;
    private Thread worker;

    public SchedulerEngine(PendingWorkStore store, StrategyRegistry strategies, String initialStrategy,
                           EngineDispatch dispatch, EngineReadiness readiness,
                           Clock clock, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(strategies, "strategies must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.readiness = Objects.requireNonNull(readiness, "readiness must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        this.activeStrategy = Objects.requireNonNull(initialStrategy, "initialStrategy must not be null");
        this.activeIndex = strategies.require(initialStrategy).newIndex();
    }

    /** The id of the strategy whose index is currently active. */
    public String activeStrategy() {
        synchronized (gate) {
            return activeStrategy;
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
            activeIndex.add(entry.ticket());
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
        while (isRunning()) {
            long observed;
            synchronized (gate) {
                // Read BEFORE the pass: anything that fires during it advances the sequence and
                // the park below returns at once instead of sleeping through the change.
                observed = wakeSequence;
            }
            long budgetMs = pass();
            if (budgetMs > 0) {
                await(budgetMs, observed);
            }
        }
    }

    private boolean isRunning() {
        synchronized (gate) {
            return running && !Thread.currentThread().isInterrupted();
        }
    }

    private void await(long budgetMs, long observed) {
        long deadlineNanos = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        synchronized (gate) {
            while (running && wakeSequence == observed) {
                long remainingNanos = deadlineNanos - nanoTime.getAsLong();
                if (remainingNanos <= 0) {
                    return;
                }
                try {
                    gate.wait(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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
        SchedulingTicket ticket = activeIndex.select(now, runnable);
        if (ticket == null) {
            return null;
        }
        PendingEntry entry = store.claim(ticket.id());
        if (entry == null) {
            // The index outlived its entry: drop the orphan rather than re-selecting it forever.
            log.warn("Dropping indexed ticket with no pending entry: {}", ticket.id());
            activeIndex.remove(ticket.id());
            deadlines.remove(ticket);
            return null;
        }
        return new Claim(ticket, activeIndex);
    }

    /** Outside the gate, except where noted: record check, lease acquisition, commit, submit. */
    @SuppressWarnings("ReferenceEquality") // Index identity, not content, decides whether the selection still stands.
    private long carry(Claim claim) {
        SchedulingTicket ticket = claim.ticket();
        if (!dispatch.isCurrent(ticket)) {
            PendingEntry dropped;
            synchronized (gate) {
                dropped = store.remove(ticket.id());
                if (dropped != null) {
                    retire(ticket);
                }
            }
            if (dropped != null) {
                dispatch.removed(dropped.task());
            }
            return 0L;
        }
        DispatchOwnership lease = dispatch.tryAcquire(ticket);
        if (lease == null) {
            synchronized (gate) {
                store.abort(ticket.id());
                activeIndex.defer(ticket.id());
                blocked.add(ticket.generation());
            }
            return CAPACITY_BLOCKED_AWAIT_MS;
        }

        InvocationTask task;
        synchronized (gate) {
            PendingEntry current = store.get(ticket.id());
            if (current == null) {
                // The claim was removed while the lease was being acquired: nothing to dispatch.
                task = null;
            } else if (claim.index() != activeIndex) {
                // The index was swapped under this selection. A provisional claim is not a
                // committed dispatch, so this attempt goes back to the index that is active now
                // and gets re-selected by the new policy.
                store.abort(ticket.id());
                activeIndex.add(ticket);
                task = null;
            } else {
                store.commit(ticket.id());
                activeIndex.remove(ticket.id());
                task = current.task();
            }
        }
        if (task == null) {
            lease.release();
            return 0L;
        }
        submit(ticket, task.withDispatchLease(lease), lease);
        return 0L;
    }

    private void submit(SchedulingTicket ticket, InvocationTask leased, DispatchOwnership lease) {
        SchedulerDispatchSupport.Result result = SchedulerDispatchSupport.dispatchWithFailureCleanup(
                leased,
                () -> dispatch.submit(leased),
                lease::release,
                failure -> dispatch.rejected(leased, failure),
                log);
        if (result == SchedulerDispatchSupport.Result.INPUT_BACKPRESSURED) {
            requeue(ticket);
        } else {
            finishSubmit(ticket);
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
                deadlines.remove(ticket);
            } else {
                activeIndex.add(ticket);
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
            deadlines.remove(ticket);
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
                activeIndex.remove(head.id());
                expired.add(entry);
            }
            // A null entry is a ticket already gone or already submitting: its dispatch is
            // committed and no longer the queue's to expire.
        }
        return expired;
    }

    /** Under the gate. */
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
     * never leave), and for the common case — expiry of the oldest ticket, which is that
     * function's head — the turn advance is exactly what a dispatch of it would have done.
     */
    private void retire(SchedulingTicket ticket) {
        activeIndex.remove(ticket.id());
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

    /** One selection in flight, with the index identity it was selected from. */
    private record Claim(SchedulingTicket ticket, SchedulingIndex index) {
    }
}
