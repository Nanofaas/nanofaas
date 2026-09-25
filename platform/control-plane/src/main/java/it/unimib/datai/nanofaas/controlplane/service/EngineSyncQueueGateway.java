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
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionController;
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionResult;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Keeps the sync admission contract ({@link SyncQueueGateway}) and delegates the actual
 * scheduling to the shared {@link SchedulerEngine}.
 *
 * <p>{@code SyncQueueConfiguration} (in the sync-queue module) builds this bean with the
 * {@link SyncQueueAdmissionController} and {@link WaitEstimator} from {@code :execution-runtime},
 * so admission thresholds and wait estimation are reused byte-for-byte rather than re-derived:
 * there is no second implementation of sync admission to drift from the one the depth cap and
 * wait estimate were validated against.
 */
public final class EngineSyncQueueGateway implements SyncQueueGateway {

    private final SyncQueueConfigSource configSource;
    private final SyncQueueAdmissionController admissionController;
    private final WaitEstimator estimator;
    /** Feeds {@code SyncQueueMetrics}' admitted/rejected counters. A plain {@link Consumer} rather
     * than the concrete metrics type, so this class stays free of a compile-time dependency on
     * the sync-queue module. */
    private final Consumer<String> onAdmitted;
    private final Consumer<String> onRejected;
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

    public EngineSyncQueueGateway(SyncQueueConfigSource configSource, // NOSONAR (java:S107): composition constructor; each argument is an injected collaborator or limit
                                  SyncQueueAdmissionController admissionController,
                                  WaitEstimator estimator,
                                  ObjectProvider<SchedulerEngine> engine,
                                  PendingWorkStore store,
                                  DispatchCapacity capacityRegistry,
                                  LongSupplier sequence,
                                  EngineInvocationEnqueuer.AdmissionProfile profile,
                                  Consumer<String> onAdmitted,
                                  Consumer<String> onRejected) {
        this(configSource, admissionController, estimator, engine, store, capacityRegistry,
                sequence, profile, onAdmitted, onRejected, Clock.systemUTC());
    }

    EngineSyncQueueGateway(SyncQueueConfigSource configSource, // NOSONAR (java:S107): composition constructor; each argument is an injected collaborator or limit
                          SyncQueueAdmissionController admissionController,
                          WaitEstimator estimator,
                          ObjectProvider<SchedulerEngine> engine,
                          PendingWorkStore store,
                          DispatchCapacity capacityRegistry,
                          LongSupplier sequence,
                          EngineInvocationEnqueuer.AdmissionProfile profile,
                          Consumer<String> onAdmitted,
                          Consumer<String> onRejected,
                          Clock clock) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
        this.admissionController = Objects.requireNonNull(admissionController, "admissionController must not be null");
        this.estimator = Objects.requireNonNull(estimator, "estimator must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.capacityRegistry = Objects.requireNonNull(capacityRegistry, "capacityRegistry must not be null");
        this.sequence = Objects.requireNonNull(sequence, "sequence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.onAdmitted = Objects.requireNonNull(onAdmitted, "onAdmitted must not be null");
        this.onRejected = Objects.requireNonNull(onRejected, "onRejected must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean enabled() {
        // Gated on the resolved admission profile, not the runtime flag alone:
        // sync-queue.enabled defaults to true and the sync-queue module is enabled by default,
        // so without this gate the both-modules default artefact (admissionProfile ==
        // FUNCTION_QUEUE) would run every sync invocation through this gateway.
        return profile == EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE && configSource.syncQueueEnabled();
    }

    @Override
    public int retryAfterSeconds() {
        return configSource.syncQueueRetryAfterSeconds();
    }

    @Override
    public void enqueueOrThrow(InvocationTask task) {
        try {
            doEnqueueOrThrow(task, clock.instant(), capacityRegistry.activeGeneration(task.functionName()));
        } catch (SyncQueueRejectedException rejected) {
            onRejected.accept(task.functionName());
            throw rejected;
        }
        onAdmitted.accept(task.functionName());
    }

    private void doEnqueueOrThrow(InvocationTask task, Instant notBefore, FunctionGeneration generation) {
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
        SyncQueueAdmissionResult result = admissionController.evaluate(task.functionName(), pendingDepth, now);
        if (!result.accepted()) {
            throw new SyncQueueRejectedException(result.reason(), configSource.syncQueueRetryAfterSeconds());
        }
        if (generation == null) {
            throw depthRejected();
        }
        TicketId id = new TicketId(task.executionId(), task.attempt());
        Instant deadline = now.plus(configSource.syncQueueMaxQueueWait());
        SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence.getAsLong(), now, notBefore, deadline);
        // PendingWorkStore's own cap is the hard depth limit: its maxPending is sync-queue.max-depth
        // whenever this profile is active (SchedulerConfiguration.pendingWorkStore), and
        // store.offer() runs under the engine's gate, so every admission is serialized against
        // the same atomic cap.
        if (!engine.getObject().enqueue(new PendingEntry(ticket, task))) {
            // Includes a generation removed after the lookup above: the engine re-checks it under
            // its gate and refuses the ticket before inserting it.
            throw depthRejected();
        }
    }

    private SyncQueueRejectedException depthRejected() {
        return new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
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
        } catch (SyncQueueRejectedException _) {
            return false;
        }
    }

    public boolean enqueue(InvocationTask task, Instant notBefore) {
        return enqueue(task, notBefore, capacityRegistry.activeGeneration(task.functionName()));
    }

    boolean enqueue(InvocationTask task, Instant notBefore, FunctionGeneration generation) {
        try {
            doEnqueueOrThrow(task, notBefore, generation);
            onAdmitted.accept(task.functionName());
            return true;
        } catch (SyncQueueRejectedException rejected) {
            onRejected.accept(task.functionName());
            return false;
        }
    }

    /** Called by the engine's dispatch adapter for every settled dispatch while the immutable
     * admission profile is SYNC_QUEUE, the only profile in which this gateway admits. */
    public void recordDispatched(String functionName, Instant now) {
        estimator.recordDispatch(functionName, now);
    }

    /** Called by the generation-removal listener so the estimator does not retain a dead function. */
    public void functionRemoved(String functionName) {
        estimator.removeFunctionState(functionName);
    }
}
