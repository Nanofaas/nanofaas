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
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Keeps the sync admission contract ({@link SyncQueueGateway}) and delegates the actual
 * scheduling to the shared {@link SchedulerEngine} instead of {@code SyncQueueService}'s own
 * queue (Task 8, issue #208).
 *
 * <p>This class deliberately has no compile-time knowledge of {@code SyncQueueAdmissionController}
 * or {@code WaitEstimator} — those stay owned by the sync-queue module, which is the only module
 * allowed to depend on them. {@code SyncQueueConfiguration} builds this bean and hands it the
 * SAME admission controller and estimator instances the module already had (via
 * {@link AdmissionCheck}, {@code onDispatched} and {@code onFunctionRemoved}), so admission
 * thresholds and wait estimation are reused byte-for-byte rather than re-derived: there is no
 * second implementation of sync admission to drift from the one the depth cap and wait estimate
 * were validated against.
 */
public final class EngineSyncQueueGateway implements SyncQueueGateway {

    /** Reused verdict of the module's own {@code SyncQueueAdmissionController.evaluate}. */
    @FunctionalInterface
    public interface AdmissionCheck {
        /** {@code null} when accepted; the reject reason otherwise. */
        SyncQueueRejectReason evaluate(String functionName, int pendingDepth, Instant now);
    }

    private final SyncQueueConfigSource configSource;
    private final AdmissionCheck admissionCheck;
    private final BiConsumer<String, Instant> onDispatched;
    private final Consumer<String> onFunctionRemoved;
    // A provider, not a direct reference: see EngineInvocationEnqueuer for why this must be
    // lazy — the engine's own dispatch calls back into a RetryScheduler, and a direct
    // constructor reference here would put this bean on that same cycle whenever it is the
    // active sync admission front.
    private final ObjectProvider<SchedulerEngine> engine;
    private final PendingWorkStore store;
    private final DispatchCapacity capacityRegistry;
    private final LongSupplier sequence;
    private final Clock clock;

    public EngineSyncQueueGateway(SyncQueueConfigSource configSource,
                                  AdmissionCheck admissionCheck,
                                  BiConsumer<String, Instant> onDispatched,
                                  Consumer<String> onFunctionRemoved,
                                  ObjectProvider<SchedulerEngine> engine,
                                  PendingWorkStore store,
                                  DispatchCapacity capacityRegistry,
                                  LongSupplier sequence) {
        this(configSource, admissionCheck, onDispatched, onFunctionRemoved, engine, store, capacityRegistry,
                sequence, Clock.systemUTC());
    }

    EngineSyncQueueGateway(SyncQueueConfigSource configSource,
                          AdmissionCheck admissionCheck,
                          BiConsumer<String, Instant> onDispatched,
                          Consumer<String> onFunctionRemoved,
                          ObjectProvider<SchedulerEngine> engine,
                          PendingWorkStore store,
                          DispatchCapacity capacityRegistry,
                          LongSupplier sequence,
                          Clock clock) {
        this.configSource = Objects.requireNonNull(configSource, "configSource must not be null");
        this.admissionCheck = Objects.requireNonNull(admissionCheck, "admissionCheck must not be null");
        this.onDispatched = Objects.requireNonNull(onDispatched, "onDispatched must not be null");
        this.onFunctionRemoved = Objects.requireNonNull(onFunctionRemoved, "onFunctionRemoved must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.capacityRegistry = Objects.requireNonNull(capacityRegistry, "capacityRegistry must not be null");
        this.sequence = Objects.requireNonNull(sequence, "sequence must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean enabled() {
        return configSource.syncQueueEnabled();
    }

    @Override
    public int retryAfterSeconds() {
        return configSource.syncQueueRetryAfterSeconds();
    }

    @Override
    public void enqueueOrThrow(InvocationTask task) {
        Instant now = clock.instant();
        int pendingDepth = store.pendingCount();
        SyncQueueRejectReason reason = admissionCheck.evaluate(task.functionName(), pendingDepth, now);
        if (reason != null) {
            throw new SyncQueueRejectedException(reason, configSource.syncQueueRetryAfterSeconds());
        }
        FunctionGeneration generation = capacityRegistry.activeGeneration(task.functionName());
        if (generation == null) {
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
        }
        TicketId id = new TicketId(task.executionId(), task.attempt());
        Instant deadline = now.plus(configSource.syncQueueMaxQueueWait());
        SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence.getAsLong(), now, now, deadline);
        if (!engine.getObject().enqueue(new PendingEntry(ticket, task))) {
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
        }
    }

    /**
     * Not a {@link RetryScheduler} override: only {@link EngineInvocationEnqueuer} is registered
     * as the (single) {@code RetryScheduler} bean, and it calls this by plain object reference
     * when the sync admission profile is active, exactly the shape the retired
     * {@code SyncQueueInvocationEnqueuer.enqueue} had.
     */
    public boolean enqueue(InvocationTask task) {
        try {
            enqueueOrThrow(task);
            return true;
        } catch (SyncQueueRejectedException rejected) {
            return false;
        }
    }

    /** Called by the engine's dispatch adapter for every dispatch, sync-origin or not. */
    public void recordDispatched(String functionName, Instant now) {
        onDispatched.accept(functionName, now);
    }

    /** Called by the generation-removal listener so the estimator does not retain a dead function. */
    public void functionRemoved(String functionName) {
        onFunctionRemoved.accept(functionName);
    }
}
