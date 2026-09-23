package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Keeps the sync admission contract ({@link SyncQueueGateway}) and delegates the actual
 * scheduling to the shared {@link SchedulerEngine}.
 *
 * <p>This class deliberately has no compile-time knowledge of {@code SyncQueueAdmissionController}
 * or {@code WaitEstimator} — those live in {@code :execution-runtime}'s {@code
 * it.unimib.datai.nanofaas.execution.admission} package as of Task 10 (issue #208), and this
 * class is deliberately not the one that references them; {@code SyncQueueConfiguration} (in the
 * sync-queue module) builds this bean and hands it the module's admission controller and
 * estimator (via {@link AdmissionCheck}, {@code onDispatched} and {@code onFunctionRemoved}), so admission
 * thresholds and wait estimation are reused byte-for-byte rather than re-derived: there is no
 * second implementation of sync admission to drift from the one the depth cap and wait estimate
 * were validated against.
 */
public final class EngineSyncQueueGateway implements SyncQueueGateway {

    /** Reused verdict of {@code SyncQueueAdmissionController.evaluate}. */
    @FunctionalInterface
    public interface AdmissionCheck {
        /** {@code null} when accepted; the reject reason otherwise. */
        SyncQueueRejectReason evaluate(String functionName, int pendingDepth, Instant now);
    }

    private final SyncQueueConfigSource configSource;
    private final AdmissionCheck admissionCheck;
    private final BiConsumer<String, Instant> onDispatched;
    private final Consumer<String> onFunctionRemoved;
    /** Feeds {@code SyncQueueMetrics}' admitted/rejected counters. A plain {@link Consumer} rather than the concrete metrics type, matching
     * {@link #onDispatched}/{@link #onFunctionRemoved} above, so this class stays free of a
     * compile-time dependency on the sync-queue module. */
    private final Consumer<String> onAdmitted;
    private final Consumer<String> onRejected;
    /** Paired with {@link #onAdmitted} exactly once per ticket. An immediate terminal event
     * defers this callback until the admission metric has been recorded. */
    private final Consumer<String> onDequeued;
    // A provider, not a direct reference: see EngineInvocationEnqueuer for why this must be
    // lazy — the engine's own dispatch calls back into a RetryScheduler, and a direct
    // constructor reference here would put this bean on that same cycle whenever it is the
    // active sync admission front.
    private final ObjectProvider<SchedulerEngine> engine;
    private final PendingWorkStore store;
    private final DispatchCapacity capacityRegistry;
    private final LongSupplier sequence;
    private final EngineInvocationEnqueuer.AdmissionProfile profile;
    private final Clock clock;

    /**
     * Functions currently being removed: a function-name-level flag, raised before the
     * generation-removal listener drains this function's pending work and cleared before it
     * grants capacity again. This narrows, but does not close to zero, the race between a
     * concurrent admission and a removal in flight.
     */
    private final Set<String> removalFences = ConcurrentHashMap.newKeySet();

    /**
     * Ticket ids registered before engine admission and removed on rejection or settlement, so
     * {@code EngineTransport} can tell a sync-origin dispatch from a function-queue-origin one
     * without threading ticket metadata through the {@link it.unimib.datai.nanofaas.execution.EngineDispatch}
     * contract: the wait estimator must be fed sync-origin dispatches only.
     */
    private final Map<TicketId, TrackedTicket> pendingSyncTickets = new ConcurrentHashMap<>();

    /** Per-ticket ordering between admission metrics and a terminal event on the worker. */
    private static final class TrackedTicket {
        private boolean admitted;
        private boolean settled;
    }

    public EngineSyncQueueGateway(SyncQueueConfigSource configSource,
                                  AdmissionCheck admissionCheck,
                                  BiConsumer<String, Instant> onDispatched,
                                  Consumer<String> onFunctionRemoved,
                                  ObjectProvider<SchedulerEngine> engine,
                                  PendingWorkStore store,
                                  DispatchCapacity capacityRegistry,
                                  LongSupplier sequence,
                                  EngineInvocationEnqueuer.AdmissionProfile profile,
                                  Consumer<String> onAdmitted,
                                  Consumer<String> onRejected,
                                  Consumer<String> onDequeued) {
        this(configSource, admissionCheck, onDispatched, onFunctionRemoved, engine, store, capacityRegistry,
                sequence, profile, onAdmitted, onRejected, onDequeued, Clock.systemUTC());
    }

    EngineSyncQueueGateway(SyncQueueConfigSource configSource,
                          AdmissionCheck admissionCheck,
                          BiConsumer<String, Instant> onDispatched,
                          Consumer<String> onFunctionRemoved,
                          ObjectProvider<SchedulerEngine> engine,
                          PendingWorkStore store,
                          DispatchCapacity capacityRegistry,
                          LongSupplier sequence,
                          EngineInvocationEnqueuer.AdmissionProfile profile,
                          Consumer<String> onAdmitted,
                          Consumer<String> onRejected,
                          Consumer<String> onDequeued,
                          Clock clock) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
        this.admissionCheck = Objects.requireNonNull(admissionCheck, "admissionCheck must not be null");
        this.onDispatched = Objects.requireNonNull(onDispatched, "onDispatched must not be null");
        this.onFunctionRemoved = Objects.requireNonNull(onFunctionRemoved, "onFunctionRemoved must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.capacityRegistry = Objects.requireNonNull(capacityRegistry, "capacityRegistry must not be null");
        this.sequence = Objects.requireNonNull(sequence, "sequence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.onAdmitted = Objects.requireNonNull(onAdmitted, "onAdmitted must not be null");
        this.onRejected = Objects.requireNonNull(onRejected, "onRejected must not be null");
        this.onDequeued = Objects.requireNonNull(onDequeued, "onDequeued must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean enabled() {
        // Fix round C1: gated on the resolved admission profile, not the runtime flag alone.
        // sync-queue.enabled defaults to true and this task made sync-queue defaultEnabled too,
        // so without this gate the both-modules default artefact (admissionProfile ==
        // FUNCTION_QUEUE) ran every sync invocation through this gateway anyway.
        return profile == EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE && configSource.syncQueueEnabled();
    }

    @Override
    public int retryAfterSeconds() {
        return configSource.syncQueueRetryAfterSeconds();
    }

    @Override
    public void enqueueOrThrow(InvocationTask task) {
        TrackedTicket tracked;
        try {
            tracked = doEnqueueOrThrow(task);
        } catch (SyncQueueRejectedException rejected) {
            onRejected.accept(task.functionName());
            throw rejected;
        }
        onAdmitted.accept(task.functionName());
        boolean settled;
        synchronized (tracked) {
            tracked.admitted = true;
            settled = tracked.settled;
        }
        if (settled) {
            onDequeued.accept(task.functionName());
        }
    }

    private TrackedTicket doEnqueueOrThrow(InvocationTask task) {
        // Two removal-fence checks: an early rejection, and a second one immediately before the commit to narrow the window a
        // concurrent removal could otherwise slip through.
        //
        // Each check is pinned by its own probe, and it is worth knowing which — they are not
        // interchangeable, and the in-window probe that drives both is not evidence for either one
        // specifically: it raises the fence before its admission starts and leaves it raised, so
        // EITHER check alone still refuses that admission, and only deleting both turns it red.
        // SyncQueueRuntimeLifecycleTest.theFirstRemovalFenceCheckRefusesAnAdmissionThatStartsFenced
        // goes red when this first check is removed, and
        // .theSecondRemovalFenceCheckRejectsAFenceRaisedWhileTheAdmissionIsInFlight goes red when
        // the second is. Removing one of the two is a red test, not a silent loosening.
        if (removalFences.contains(task.functionName())) {
            throw depthRejected();
        }
        Instant now = clock.instant();
        // Valid as a sync-scoped depth only because enabled() now confines this gateway to the
        // SYNC_QUEUE profile: nothing else admits into the engine while it is active, so
        // store.pendingCount() is the sync queue's depth.
        //
        // Deliberately read OUTSIDE the engine's gate, unlike every other use of the store: this
        // is the admission estimate, and the gate is taken by the engine.enqueue below it. The
        // read is therefore an unprotected snapshot of a store that the engine loop mutates
        // concurrently — see PendingWorkStore.pendingCount()'s own note. Nothing is decided on
        // it: it feeds a threshold, and the cap that binds is the store's own, applied under the
        // gate inside enqueue() — which is also why the sync admission profile's max-depth
        // (SchedulerConfiguration.pendingWorkStore) is the real limit here, not this number.
        int pendingDepth = store.pendingCount();
        SyncQueueRejectReason reason = admissionCheck.evaluate(task.functionName(), pendingDepth, now);
        if (reason != null) {
            throw new SyncQueueRejectedException(reason, configSource.syncQueueRetryAfterSeconds());
        }
        if (removalFences.contains(task.functionName())) {
            throw depthRejected();
        }
        FunctionGeneration generation = capacityRegistry.activeGeneration(task.functionName());
        if (generation == null) {
            throw depthRejected();
        }
        TicketId id = new TicketId(task.executionId(), task.attempt());
        Instant deadline = now.plus(configSource.syncQueueMaxQueueWait());
        SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence.getAsLong(), now, now, deadline);
        // PendingWorkStore's own cap is the hard depth limit: the store's maxPending is set to sync-queue.max-depth exactly whenever this
        // profile is active (SchedulerConfiguration.pendingWorkStore), and store.offer() runs
        // under the engine's own gate, so this admission and every other one are serialized
        // against the same atomic cap.
        TrackedTicket tracked = new TrackedTicket();
        if (pendingSyncTickets.putIfAbsent(id, tracked) != null) {
            throw depthRejected();
        }
        boolean enqueued = false;
        try {
            enqueued = engine.getObject().enqueue(new PendingEntry(ticket, task));
        } finally {
            if (!enqueued) {
                pendingSyncTickets.remove(id, tracked);
            }
        }
        if (!enqueued) {
            throw depthRejected();
        }
        // Removal can finish after the generation read but before enqueue, missing this ticket
        // in its drain. Withdraw it through the engine so lifecycle cleanup runs exactly once.
        if (!generation.equals(capacityRegistry.activeGeneration(task.functionName()))) {
            engine.getObject().remove(id);
            throw depthRejected();
        }
        return tracked;
    }

    private SyncQueueRejectedException depthRejected() {
        return new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
    }

    /** Raised by the generation-removal listener before it drains this function's pending work. */
    public void raiseRemovalFence(String functionName) {
        removalFences.add(functionName);
    }

    /** Cleared by the generation-registration listener before it grants capacity. */
    public void clearRemovalFence(String functionName) {
        removalFences.remove(functionName);
    }

    /**
     * {@code true}, and settles this gateway's own bookkeeping, only when {@code id} was
     * admitted through this gateway. Called by {@code EngineTransport} on every terminal engine
     * event (submit, expired, removed) so the estimator is fed sync-origin dispatches only and
     * this map never leaks an id whose ticket left the engine without reaching submit.
     *
     * <p>Each terminal path calls this method. When settlement precedes the admission metric,
     * the decrement waits until {@link #enqueueOrThrow} records that metric.
     */
    public boolean settleIfSyncOrigin(String functionName, TicketId id) {
        TrackedTicket tracked = pendingSyncTickets.remove(id);
        if (tracked == null) {
            return false;
        }
        boolean admitted;
        synchronized (tracked) {
            admitted = tracked.admitted;
            if (!admitted) {
                tracked.settled = true;
            }
        }
        if (admitted) {
            onDequeued.accept(functionName);
        }
        return true;
    }

    /**
     * Not a {@link RetryScheduler} override: only {@link EngineInvocationEnqueuer} is registered
     * as the (single) {@code RetryScheduler} bean, and it calls this by plain object reference
     * when the sync admission profile is active.
     */
    public boolean enqueue(InvocationTask task) {
        try {
            enqueueOrThrow(task);
            return true;
        } catch (SyncQueueRejectedException rejected) {
            return false;
        }
    }

    /** Called by the engine's dispatch adapter only after {@link #settleIfSyncOrigin} confirms
     * the dispatched ticket originated from this gateway. */
    public void recordDispatched(String functionName, Instant now) {
        onDispatched.accept(functionName, now);
    }

    /** Called by the generation-removal listener so the estimator does not retain a dead function. */
    public void functionRemoved(String functionName) {
        onFunctionRemoved.accept(functionName);
    }
}
