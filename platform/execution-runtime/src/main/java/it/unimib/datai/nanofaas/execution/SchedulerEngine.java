package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The single scheduling loop. It owns the pending work, the active {@link SchedulingIndex} and
 * the wake sequence; the index owns nothing but ticket order. One worker thread runs
 * {@link #tick()} in a loop and parks on the engine's own monitor between passes, so every
 * index operation is serialized without the index knowing about threads at all.
 *
 * <h2>Lock order</h2>
 * The only lock ever taken while holding the engine's gate is a capacity-registry entry lock:
 * {@link EngineReadiness} and the generation check in {@link #enqueue(PendingEntry, int)} read
 * the registry under the gate. The registry fires its callbacks (which can reach the engine)
 * only after releasing that entry lock, so {@code gate -> registry entry} is the one outward
 * edge and it cannot close a cycle. Lease acquisition, provider calls, record inspection, submit
 * and every lifecycle notification happen outside the gate. That is what makes a
 * {@code record -> gate} order (a retry publishing into the engine after releasing the execution
 * record) safe: the reverse edge {@code gate -> record} does not exist. Anything may call
 * {@link #signal()}, {@link #enqueue} or {@link #remove} while holding its own lock, <em>except
 * a capacity-registry entry lock</em>, which would invert the one permitted edge. See ADR 0002
 * §3.
 *
 * <h2>One selection per pass</h2>
 * A pass reaps due queue deadlines, then makes at most one selection and carries it to a
 * decision. A generation whose lease could not be acquired is dropped from consideration until
 * the next {@link #signal()}: the passive index contract deliberately leaves that to the
 * engine's wake sequence.
 */
public final class SchedulerEngine implements AutoCloseable, SchedulerControl {

    private static final Logger log = LoggerFactory.getLogger(SchedulerEngine.class);
    private static final String FUNCTION_NAME_REQUIRED = "functionName must not be null";

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
    /** Identity check against the generation authority: is this ticket's generation still the
     * function's active one? Never a capacity check. Evaluated under {@link #gate}. */
    private final Predicate<FunctionGeneration> generationActive;
    private final Clock clock;
    private final LongSupplier nanoTime;

    private final Object gate = new Object();

    /** Queue deadlines of the tickets that have one, soonest first. Guarded by {@link #gate}. */
    private final TreeSet<SchedulingTicket> deadlines = new TreeSet<>(
            Comparator.comparing(SchedulingTicket::queueDeadline)
                    .thenComparingLong(SchedulingTicket::sequence));
    /** Future eligibility, independent of the selected strategy. Guarded by {@link #gate}. */
    private final TreeSet<SchedulingTicket> delayed = new TreeSet<>(
            Comparator.comparing(SchedulingTicket::notBefore)
                    .thenComparingLong(SchedulingTicket::sequence)
                    .thenComparing(t -> t.id().executionId())
                    .thenComparingInt(t -> t.id().attempt()));
    /** Generations whose lease could not be acquired since the last wake. Guarded by {@link #gate}. */
    private final Set<FunctionGeneration> blocked = new HashSet<>();
    /**
     * Removals that arrived while their ticket was already submitting. {@link PendingWorkStore}
     * deliberately gives no handle on a committed dispatch, so the request is held here and
     * applied if — and only if — that dispatch comes back for a requeue. Guarded by {@link #gate}.
     */
    private final Set<TicketId> cancelRequests = new HashSet<>();
    /** Tickets retained by each execution, including overlapping retry publication during submit.
     * Terminal notifications use this lookup instead of scanning unrelated backlog. Guarded by gate. */
    private final Map<String, Set<TicketId>> executionTickets = new HashMap<>();

    /**
     * The active policy and its index, published as one immutable pair. Written only under
     * {@link #gate}; volatile so {@link #snapshot()} can read a consistent pair without taking
     * it. The epoch is what a provisional claim carries, so a selection that spans a switch is
     * detected by value rather than by index identity.
     */
    private volatile ActiveScheduler active; // NOSONAR (java:S3077): thread-safe or immutable value replaced wholesale
    private long wakeSequence;
    private boolean running;
    private boolean disposed;
    private Thread worker;

    /** Per-function queue caps supplied by the most recent admission attempt. Written only under
     * {@link #gate} (set by {@link #enqueue(PendingEntry, int)}, cleared by {@link #removeAllFor});
     * concurrent so the advisory {@link #isQueueFull} can read it without the gate. */
    private final Map<String, Integer> queueCaps = new ConcurrentHashMap<>();

    /** Function names whose removal drained the pending index but may still hold a physically
     * active lease; reconciled at the end of every {@link #pass()}. Guarded by {@link #gate}. */
    private final Set<String> draining = new HashSet<>();
    /** Registered once, on the engine (never by a strategy): fired with a function's name the
     * first time its reservation count reaches zero after {@link #markDraining} was called for
     * it. */
    private final List<Consumer<String>> drainListeners = new CopyOnWriteArrayList<>();

    /** No-op until {@link #setSwitchObserver} binds one; observation is best-effort and must
     * never affect a switch's own outcome — see {@link #switchTo}. */
    private volatile SwitchObserver switchObserver = (strategy, outcome, durationNanos) -> { }; // NOSONAR (java:S3077): thread-safe or immutable value replaced wholesale

    public SchedulerEngine(PendingWorkStore store, StrategyRegistry strategies, String initialStrategy, // NOSONAR (java:S107): composition constructor; each argument is an injected collaborator or limit
                           EngineDispatch dispatch, EngineReadiness readiness,
                           Predicate<FunctionGeneration> generationActive,
                           Clock clock, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.strategies = Objects.requireNonNull(strategies, "strategies must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.readiness = Objects.requireNonNull(readiness, "readiness must not be null");
        this.generationActive = Objects.requireNonNull(generationActive, "generationActive must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        Objects.requireNonNull(initialStrategy, "initialStrategy must not be null");
        SchedulingStrategy initial = strategies.require(initialStrategy);
        this.active = new ActiveScheduler(initial.id(), initial.newIndex(), 0L);
    }

    @Override
    public SchedulerSelection snapshot() {
        return new SchedulerSelection(active.id(), strategies.ids());
    }

    /** A point-in-time view of the pending population; see {@link EngineQueueSnapshot}. Cheap
     * enough to compute under the gate on demand — it is not on any Prometheus scrape path. */
    public EngineQueueSnapshot snapshotQueues() {
        Instant now = clock.instant();
        synchronized (gate) {
            List<PendingEntry> pendingEntries = store.snapshotPending();
            int delayed = 0;
            Map<FunctionGeneration, Integer> perGeneration = new HashMap<>();
            for (PendingEntry entry : pendingEntries) {
                if (entry.ticket().notBefore().isAfter(now)) {
                    delayed++;
                }
                perGeneration.merge(entry.ticket().generation(), 1, Integer::sum);
            }
            return new EngineQueueSnapshot(pendingEntries.size(), store.claimedCount(),
                    store.submittingCount(), delayed, perGeneration);
        }
    }

    /** Outcome of one {@link #switchTo} call, reported to a {@link SwitchObserver}. */
    public enum SwitchOutcome { COMMITTED, NOOP, REFUSED }

    /**
     * Reports every {@link #switchTo} outcome and its wall-clock duration. Deliberately not part
     * of the switch's correctness transaction (nothing fallible follows the linearization
     * point) — a throwing observer is caught and logged, never allowed to make a
     * committed switch look like it failed, or vice versa.
     */
    @FunctionalInterface
    public interface SwitchObserver {
        void observe(String strategy, SwitchOutcome outcome, long durationNanos);
    }

    /** Registered once, by the composition root. Not required; defaults to a no-op. */
    public void setSwitchObserver(SwitchObserver observer) {
        this.switchObserver = Objects.requireNonNull(observer, "observer must not be null");
    }

    /**
     * Registered once, on the engine — never by a strategy (the plan's own constraint: indexes
     * own ticket order only). Fired the first time a function marked {@link #markDraining} has no
     * reservation left, so a caller can retire per-function meters without doing so while a
     * physically active attempt still holds a lease.
     */
    public void addDrainListener(Consumer<String> listener) {
        drainListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * Marks {@code functionName} as awaiting drain: the next time its reservation count reaches
     * zero (checked at the end of every {@link #pass()}, not scanned on demand), every registered
     * drain listener fires once with its name.
     */
    public void markDraining(String functionName) {
        Objects.requireNonNull(functionName, FUNCTION_NAME_REQUIRED);
        synchronized (gate) {
            draining.add(functionName);
        }
    }

    /**
     * Cancels a pending {@link #markDraining} for {@code functionName} without firing its
     * listeners. Call this on re-registration: a function removed and re-registered before its
     * old generation finished draining must not have the new generation's freshly-registered
     * meters torn down when the old generation's last reservation happens to settle afterwards.
     */
    public void clearDraining(String functionName) {
        Objects.requireNonNull(functionName, FUNCTION_NAME_REQUIRED);
        synchronized (gate) {
            draining.remove(functionName);
        }
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
        long startNanos = nanoTime.getAsLong();
        SwitchOutcome outcome = SwitchOutcome.REFUSED;
        try {
            synchronized (gate) {
                ActiveScheduler current = active;
                if (current.id().equals(target.id())) {
                    // Same strategy: a no-op for indexes and for the worker, by contract.
                    outcome = SwitchOutcome.NOOP;
                    return;
                }
                SchedulingIndex superseded;
                try {
                    SchedulingIndex candidate = prepare(target);
                    // Linearization point. Every validation is behind us and nothing below can
                    // fail in a way that would have to undo this: discarding the old index is
                    // isolated cleanup, not part of the transaction.
                    active = new ActiveScheduler(target.id(), candidate, current.epoch() + 1);
                    superseded = current.index();
                    outcome = SwitchOutcome.COMMITTED;
                } finally {
                    // Whether committed or refused, the selector re-examines everything: a
                    // generation blocked against the old index must not stay blocked against an
                    // index that has never been consulted for it.
                    wake();
                }
                discard(superseded);
            }
            log.info("Scheduler strategy switched to {}", target.id()); // NOSONAR (java:S2629): cheap accessors
        } finally {
            // Observation is not part of the correctness transaction above:
            // it runs after every possible outcome, including a thrown SchedulerSwitchException,
            // and a throwing observer must never be allowed to turn a committed switch into a
            // reported failure or vice versa.
            long durationNanos = nanoTime.getAsLong() - startNanos;
            try {
                switchObserver.observe(target.id(), outcome, durationNanos);
            } catch (RuntimeException observerFailure) {
                log.warn("Switch observer failed for strategy {}", target.id(), observerFailure);
            }
        }
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
        if (store.reservedCount() > MAX_SWITCH_REBUILD_TICKETS) {
            throw new SchedulerSwitchException(SchedulerSwitchException.Reason.TEMPORARY_CAP,
                    "Too much reserved work to switch scheduler: " + store.reservedCount()
                            + " tickets, cap " + MAX_SWITCH_REBUILD_TICKETS);
        }
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
                if (!delayed.contains(entry.ticket())) {
                    candidate.add(entry.ticket());
                }
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
     * Admits work with no per-function cap: one reservation in the store and one ticket in the
     * ready or delayed index, or nothing at all when the store is full or the ticket's generation is no
     * longer the function's active one (see {@link #enqueue(PendingEntry, int)}). Safe to call from
     * any thread, including one holding an execution record's monitor, but not while holding a
     * capacity-registry entry lock.
     */
    public boolean enqueue(PendingEntry entry) {
        return enqueue(entry, Integer.MAX_VALUE);
    }

    /**
     * Admits {@code entry} only while its function name holds fewer than {@code perFunctionCap}
     * reservations (pending, claimed or submitting, including an older generation's). The cap
     * check and the insertion are one step under {@link #gate}, so two admissions can never both
     * take the last slot. The most recent admission attempt supplies the cap that
     * {@link #isQueueFull} reports. A ticket whose generation is no longer active is refused
     * before anything is recorded, and receives no {@link EngineDispatch#removed} event: the
     * caller keeps its own rejection cleanup.
     */
    public boolean enqueue(PendingEntry entry, int perFunctionCap) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (perFunctionCap <= 0) {
            throw new IllegalArgumentException("perFunctionCap must be positive");
        }
        synchronized (gate) {
            // Before any state changes: a stale generation never becomes a reservation, and never
            // recreates cap metadata a removal just cleared. Removal retires the generation before
            // it drains under this gate, so a concurrent admission is either drained or refused.
            if (disposed || !generationActive.test(entry.ticket().generation())) {
                return false;
            }
            String name = entry.ticket().generation().functionName();
            queueCaps.put(name, perFunctionCap);
            if (store.reservedCount(name) >= perFunctionCap || !store.offer(entry)) {
                return false;
            }
            try {
                indexEligible(entry.ticket(), clock.instant());
                track(entry.ticket());
            } catch (RuntimeException | Error failure) {
                store.remove(entry.ticket().id());
                retire(entry.ticket());
                throw failure;
            }
            wake();
            return true;
        }
    }

    /** Advisory: whether {@code functionName} is at the cap its last admission attempt supplied.
     * {@code false} for a name never admitted, or removed since. Runs on every invocation's
     * precheck, so it never takes the gate: both reads are concurrent-safe, and a momentarily
     * stale answer is fine because {@link #enqueue(PendingEntry, int)} stays authoritative. */
    public boolean isQueueFull(String functionName) {
        Integer cap = queueCaps.get(functionName);
        return cap != null && store.reservedCount(functionName) >= cap;
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
     * Withdraws every pending or claimed ticket whose generation belongs to {@code functionName} — e.g. on
     * function removal, so queued callers are terminated rather than stranded — and reports each
     * through {@link EngineDispatch#removed}, exactly as a single {@link #remove(TicketId)}
     * would for each.
     *
     * <p>The scan and every removal happen under {@link #gate} in one section, the same
     * requirement every other read or mutation of {@link #store} already observes
     * ({@code PendingWorkStore} documents that it does not lock internally): callers must not
     * read {@link PendingWorkStore#snapshotPending()} themselves and remove by id afterwards,
     * since that runs concurrently with the worker's own {@code offer}/{@code finishSubmit}
     * mutations of the same {@code LinkedHashMap} and can both throw
     * ({@code ConcurrentModificationException} out of the snapshot's stream) and silently miss
     * entries. A submitting ticket belongs to the lifecycle; its cancellation is remembered in
     * case input backpressure returns it to the queue.
     */
    public void removeAllFor(String functionName) {
        Objects.requireNonNull(functionName, FUNCTION_NAME_REQUIRED);
        List<PendingEntry> removed = new ArrayList<>();
        synchronized (gate) {
            queueCaps.remove(functionName);
            for (PendingEntry entry : store.snapshotAll()) {
                if (entry.ticket().generation().functionName().equals(functionName)) {
                    PendingEntry taken = store.remove(entry.ticket().id());
                    if (taken != null) {
                        retire(taken.ticket());
                        removed.add(taken);
                    } else {
                        cancelRequests.add(entry.ticket().id());
                    }
                }
            }
        }
        for (PendingEntry entry : removed) {
            dispatch.removed(entry.task());
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
            if (disposed) {
                throw new IllegalStateException("Scheduler engine is disposed");
            }
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
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
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
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** Permanently releases retained work; unlike close(), this owner cannot restart. */
    public void dispose() {
        List<PendingEntry> removed;
        synchronized (gate) {
            disposed = true;
            queueCaps.clear();
            removed = removeMatching(entry -> true);
        }
        close();
        for (PendingEntry entry : removed) {
            dispatch.removed(entry.task());
        }
    }

    /** Terminal execution cleanup, including a removal racing a committed submit. */
    public void removeExecution(String executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        List<PendingEntry> removed;
        synchronized (gate) {
            Set<TicketId> tickets = executionTickets.get(executionId);
            if (tickets == null) {
                return;
            }
            removed = new ArrayList<>();
            for (TicketId id : List.copyOf(tickets)) {
                PendingEntry taken = store.remove(id);
                if (taken != null) {
                    retire(taken.ticket());
                    removed.add(taken);
                } else {
                    cancelRequests.add(id);
                }
            }
        }
        for (PendingEntry entry : removed) {
            dispatch.removed(entry.task());
        }
    }

    /** Under the gate. Submitting entries can only be cancelled if they return for requeue. */
    private List<PendingEntry> removeMatching(Predicate<PendingEntry> matches) {
        List<PendingEntry> removed = new ArrayList<>();
        for (PendingEntry entry : store.snapshotAll()) {
            if (matches.test(entry)) {
                PendingEntry taken = store.remove(entry.ticket().id());
                if (taken != null) {
                    retire(taken.ticket());
                    removed.add(taken);
                } else {
                    cancelRequests.add(entry.ticket().id());
                }
            }
        }
        return removed;
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
            // signals must not park a function forever (the retired loop's CAPACITY_BLOCKED_AWAIT_MS
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
            long remainingNanos = deadlineNanos - nanoTime.getAsLong();
            while (running && wakeSequence == observed && remainingNanos > 0) {
                try {
                    gate.wait(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
                remainingNanos = deadlineNanos - nanoTime.getAsLong();
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
        List<PendingEntry> expired = List.of();
        Claim claim;
        int promoted;
        try {
            synchronized (gate) {
                expired = reapExpired(now);
                promoted = promoteDue(now);
                claim = selectAndClaim(now);
            }
        } finally {
            // Even a failing promotion must deliver the expiries already removed from the store.
            for (PendingEntry entry : expired) {
                dispatch.expired(entry.task());
            }
        }
        long budgetMs;
        if (claim == null) {
            budgetMs = expired.isEmpty() && promoted == 0 ? idleBudgetMs() : 0L;
        } else {
            carry(claim);
            budgetMs = 0L;
        }
        checkDrained();
        return budgetMs;
    }

    /**
     * Reconciles {@link #draining} against the store's reservation counts, bounded by the (normally
     * empty, at most a handful of entries) set of functions actually awaiting drain — never a
     * scan of the backlog itself. Listener callbacks run outside {@link #gate}, mirroring every
     * other lifecycle notification in this class.
     */
    private void checkDrained() {
        if (draining.isEmpty()) {
            // Cheap racy read before taking the gate: draining is only ever cleared or drained,
            // never resurrected without a fresh markDraining, so a false negative here just waits
            // for the next pass and a false positive is impossible (isEmpty() cannot lie true
            // when it is not).
            return;
        }
        List<String> drained = new ArrayList<>();
        synchronized (gate) {
            draining.removeIf(name -> {
                if (reservedCount(name) == 0) {
                    drained.add(name);
                    return true;
                }
                return false;
            });
        }
        for (String name : drained) {
            for (Consumer<String> listener : drainListeners) {
                try {
                    listener.accept(name);
                } catch (RuntimeException failure) {
                    log.warn("Drain listener failed for function {}", name, failure);
                }
            }
        }
    }

    /** Total reservations (pending + claimed + submitting) held for {@code functionName}; never
     * scans the store. Safe from any thread: see {@link PendingWorkStore#reservedCount(String)}. */
    public int reservedCount(String functionName) {
        return store.reservedCount(functionName);
    }

    long idleBudgetMs() {
        synchronized (gate) {
            Instant now = clock.instant();
            long budget = store.pendingCount() == 0 ? EMPTY_QUEUE_AWAIT_MS : CAPACITY_BLOCKED_AWAIT_MS;
            if (!deadlines.isEmpty()) {
                budget = boundWait(budget, now, deadlines.first().queueDeadline());
            }
            if (!delayed.isEmpty()) {
                budget = boundWait(budget, now, delayed.first().notBefore());
            }
            return budget;
        }
    }

    private static long boundWait(long safetyMs, Instant now, Instant event) {
        if (!event.isAfter(now)) {
            return 0;
        }
        Instant safetyEnd;
        try {
            safetyEnd = now.plusMillis(safetyMs);
        } catch (java.time.DateTimeException overflow) {
            safetyEnd = Instant.MAX;
        }
        if (!event.isBefore(safetyEnd)) {
            return safetyMs;
        }
        Duration wait = Duration.between(now, event);
        return Math.max(1, wait.toMillis() + (wait.getNano() % 1_000_000 == 0 ? 0 : 1));
    }

    /** Under the gate. Only tickets in the index are claimed, and the index holds no submitting ticket. */
    private Claim selectAndClaim(Instant now) {
        Predicate<FunctionGeneration> runnable =
                generation -> !blocked.contains(generation) && readiness.runnable(generation);
        ActiveScheduler scheduler = active;
        SchedulingTicket ticket = scheduler.index().select(now, runnable);
        if (ticket == null) {
            scheduler.index().advanceScanWindow();
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
    private void carry(Claim claim) {
        SchedulingTicket ticket = claim.ticket();
        // A claim is provisional and lives only inside this method. tryAcquire and release are
        // both pluggable lifecycle code: if either of them throws, the loop's barrier
        // catches it, and without this guard the claim would stay in the store forever —
        // reserved, unselectable and invisible to the deadline reap.
        boolean claimSettled = false;
        try {
            DispatchOwnership lease = dispatch.tryAcquire(ticket);
            if (lease == null) {
                synchronized (gate) {
                    abortClaim(claim);
                    blocked.add(ticket.generation());
                }
                claimSettled = true;
                // Pass again at once: the next selection skips this blocked generation, so other
                // runnable work goes out now, and idleBudgetMs parks if nothing else can run.
                return;
            }

            InvocationTask task;
            synchronized (gate) {
                PendingEntry current = store.get(ticket.id());
                if (current == null) {
                    // The claim was removed while the lease was being acquired: nothing to dispatch.
                    task = null;
                } else if (claim.epoch() != active.epoch() || ticket.notBefore().isAfter(clock.instant())) {
                    // A switch invalidates the policy decision; a backward clock can invalidate
                    // eligibility. Return the provisional claim to the current ready/delayed
                    // location before releasing its lease outside the gate.
                    abortClaim(claim);
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
                return;
            }
            submit(ticket, task.withDispatchLease(lease), lease);
        } finally {
            if (!claimSettled) {
                synchronized (gate) {
                    abortClaim(claim);
                }
            }
        }
    }

    /** Under the gate: a switched index never saw the provisional claim during its rebuild. */
    private void abortClaim(Claim claim) {
        SchedulingTicket ticket = claim.ticket();
        if (store.get(ticket.id()) == null) {
            return; // Removed while lifecycle code ran outside the gate.
        }
        store.abort(ticket.id());
        Instant now = clock.instant();
        if (claim.epoch() != active.epoch()) {
            indexEligible(ticket, now);
        } else if (ticket.notBefore().isAfter(now)) {
            active.index().remove(ticket.id());
            indexEligible(ticket, now);
        } else {
            active.index().defer(ticket.id());
        }
    }

    /** Returns true only for input backpressure whose lease was released. */
    private boolean dispatchWithFailureCleanup(InvocationTask task, DispatchOwnership lease) {
        try {
            dispatch.submit(task);
            return false;
        } catch (InvocationQuotaExceededException _) {
            lease.release();
            log.debug("Input capacity blocked dispatch for execution {}", task.executionId());
            return true;
        } catch (RuntimeException | Error ex) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            try {
                lease.release();
            } finally {
                dispatch.rejected(task, ex);
            }
            log.error("Dispatch failed for execution {}: {}", task.executionId(), ex.getMessage(), ex);
            return false;
        }
    }

    private void submit(SchedulingTicket ticket, InvocationTask leased, DispatchOwnership lease) {
        boolean inputBackpressured = false;
        try {
            inputBackpressured = dispatchWithFailureCleanup(leased, lease);
        } finally {
            // A throwing cleanup still settles the reservation; only confirmed input
            // backpressure requeues the ticket and retains its reservation. A ticket left in
            // `submitting` would hold its reservation forever: no index holds it, and the
            // deadline reap cannot remove it. When the settlement is unknown the reservation is
            // released; concluding that execution is the lifecycle's job, not the queue's.
            if (inputBackpressured) {
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
                untrackExecution(ticket.id());
                if (ticket.queueDeadline() != null) {
                    deadlines.remove(ticket);
                }
            } else {
                indexEligible(ticket, clock.instant());
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
            untrackExecution(ticket.id());
            cancelRequests.remove(ticket.id());
            if (ticket.queueDeadline() != null) {
                deadlines.remove(ticket);
            }
        }
    }

    /** Under the gate. Drops every pending ticket already past its queue deadline, bounded. */
    private List<PendingEntry> reapExpired(Instant now) {
        if (deadlines.isEmpty()) {
            return List.of();
        }
        List<PendingEntry> expired = new ArrayList<>();
        int visited = 0;
        while (visited < MAX_EXPIRED_PER_PASS && !deadlines.isEmpty()) {
            SchedulingTicket head = deadlines.first();
            if (head.queueDeadline().isAfter(now)) {
                break;
            }
            deadlines.pollFirst();
            visited++;
            PendingEntry entry = store.remove(head.id());
            if (entry != null) {
                // Out-of-band removal: the per-function index treats this exactly as it treats a
                // dispatch of that function's turn (see remove()).
                removeEligibility(head);
                untrackExecution(head.id());
                expired.add(entry);
            }
            // A null entry is a ticket already gone or already submitting: its dispatch is
            // committed and no longer the queue's to expire.
        }
        return expired;
    }

    /** Under the gate. Exactly one eligibility location for each pending ticket. */
    private void indexEligible(SchedulingTicket ticket, Instant now) {
        if (ticket.notBefore().isAfter(now)) {
            delayed.add(ticket);
        } else {
            active.index().add(ticket);
        }
    }

    private void removeEligibility(SchedulingTicket ticket) {
        if (!delayed.remove(ticket)) {
            active.index().remove(ticket.id());
        }
    }

    private int promoteDue(Instant now) {
        int promoted = 0;
        while (promoted < 64 && !delayed.isEmpty() && !delayed.first().notBefore().isAfter(now)) {
            SchedulingTicket ticket = delayed.first();
            if (ticket.queueDeadline() != null && !ticket.queueDeadline().isAfter(now)) {
                break;
            }
            // Add first: an index failure leaves the delayed reservation reachable for retry.
            active.index().add(ticket);
            delayed.remove(ticket);
            promoted++;
        }
        return promoted;
    }

    /** Reservation lookup and deadlines are independent of eligibility, idempotent across requeue. */
    private void track(SchedulingTicket ticket) {
        executionTickets.computeIfAbsent(ticket.id().executionId(), ignored -> new HashSet<>())
                .add(ticket.id());
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
     *       and fairness work, so it is not widened for a defect that loses nothing. The
     *       fairness tests measure this divergence; {@code withdraw()} is added only if it
     *       proves material there.</li>
     * </ul>
     */
    private void retire(SchedulingTicket ticket) {
        untrackExecution(ticket.id());
        removeEligibility(ticket);
        if (ticket.queueDeadline() != null) {
            deadlines.remove(ticket);
        }
    }

    /** Under the gate. Forget only the settled attempt; a published retry may still be retained. */
    private void untrackExecution(TicketId id) {
        Set<TicketId> tickets = executionTickets.get(id.executionId());
        if (tickets != null && tickets.remove(id) && tickets.isEmpty()) {
            executionTickets.remove(id.executionId());
        }
    }

    /** Under the gate. */
    private void wake() {
        wakeSequence++;
        blocked.clear();
        gate.notifyAll(); // NOSONAR (java:S2273): wake() is only called under synchronized (gate)
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
