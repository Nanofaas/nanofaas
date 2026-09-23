package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The single admission/retry front door onto the composed {@link SchedulerEngine}, regardless
 * of how many {@code SchedulingStrategy} beans are on the classpath (Task 8, issue #208).
 *
 * <p>{@link InvocationEnqueuer#enqueue} and {@link RetryScheduler#enqueue} share one method, as
 * the interfaces already declare it identically: a fresh admission and a retry both become one
 * ticket. Which admission
 * profile is active decides where that ticket goes:
 * <ul>
 *   <li>{@link AdmissionProfile#FUNCTION_QUEUE} — admits straight into the engine: no
 *       depth/wait-time gate beyond the per-function cap the engine checks atomically with
 *       admission ({@link SchedulerEngine#enqueue(PendingEntry, int)}).</li>
 *   <li>{@link AdmissionProfile#SYNC_QUEUE} — this bean's {@code enqueue} is only ever reached
 *       for a <em>retry</em> here (a fresh sync admission goes through
 *       {@link EngineSyncQueueGateway#enqueueOrThrow} directly, never through this class), and it
 *       delegates to the same gate a fresh admission would have used.</li>
 *   <li>{@link AdmissionProfile#DIRECT} — no queue module resolved for admission; refuses.</li>
 * </ul>
 */
public final class EngineInvocationEnqueuer implements InvocationEnqueuer, RetryScheduler {

    /**
     * The scheduler's product-compatibility surface: three admission profiles, resolved once at
     * startup by {@code SchedulerConfiguration}. Lives here (in {@code controlplane.service})
     * rather than in {@code controlplane.config} so that package depends on this one and not the
     * other way around — {@code SchedulerConfiguration} already has to reference
     * {@code EngineInvocationEnqueuer}/{@code EngineSyncQueueGateway} directly.
     */
    public enum AdmissionProfile { FUNCTION_QUEUE, SYNC_QUEUE, DIRECT }

    // A provider, not a direct reference: SchedulerEngine's own dispatch calls back into the
    // core's InvocationDispatch, which needs a RetryScheduler (this bean) to schedule retries —
    // a direct constructor reference here would make the engine bean depend on itself through
    // this class. Resolved lazily, only when admission actually runs, well after the container
    // has finished wiring both beans.
    private final ObjectProvider<SchedulerEngine> engine;
    private final DispatchCapacity capacityRegistry;
    private final LongSupplier sequence;
    private final AdmissionProfile profile;
    private final boolean asyncEnabled;
    private final ObjectProvider<EngineSyncQueueGateway> syncGateway;
    private final Clock clock;

    public EngineInvocationEnqueuer(ObjectProvider<SchedulerEngine> engine,
                                    DispatchCapacity capacityRegistry,
                                    LongSupplier sequence,
                                    AdmissionProfile profile,
                                    boolean asyncEnabled,
                                    ObjectProvider<EngineSyncQueueGateway> syncGateway) {
        this(engine, capacityRegistry, sequence, profile, asyncEnabled, syncGateway, Clock.systemUTC());
    }

    EngineInvocationEnqueuer(ObjectProvider<SchedulerEngine> engine,
                             DispatchCapacity capacityRegistry,
                             LongSupplier sequence,
                             AdmissionProfile profile,
                             boolean asyncEnabled,
                             ObjectProvider<EngineSyncQueueGateway> syncGateway,
                             Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.capacityRegistry = Objects.requireNonNull(capacityRegistry, "capacityRegistry must not be null");
        this.sequence = Objects.requireNonNull(sequence, "sequence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.asyncEnabled = asyncEnabled;
        this.syncGateway = Objects.requireNonNull(syncGateway, "syncGateway must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public QueueStrategy queueStrategy() {
        return profile == AdmissionProfile.FUNCTION_QUEUE ? QueueStrategy.FUNCTION_QUEUE : QueueStrategy.DIRECT;
    }

    @Override
    public boolean supportsAsync() {
        return asyncEnabled;
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        if (profile == AdmissionProfile.SYNC_QUEUE) {
            EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
            return gateway != null && gateway.enqueue(task);
        }
        if (profile != AdmissionProfile.FUNCTION_QUEUE) {
            return false;
        }
        return admitDirect(task);
    }

    @Override
    public boolean isQueueFull(String functionName) {
        // Advisory only (enqueue remains authoritative).
        return profile == AdmissionProfile.FUNCTION_QUEUE
                && engine.getObject().isQueueFull(functionName);
    }

    private boolean admitDirect(InvocationTask task) {
        int cap = task.functionSpec() != null && task.functionSpec().queueSize() != null
                ? Math.max(1, task.functionSpec().queueSize())
                : Integer.MAX_VALUE;
        FunctionGeneration generation = capacityRegistry.activeGeneration(task.functionName());
        if (generation == null) {
            return false;
        }
        Instant now = clock.instant();
        TicketId id = new TicketId(task.executionId(), task.attempt());
        SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence.getAsLong(), now, now, null);
        SchedulerEngine schedulerEngine = engine.getObject();
        boolean admitted = schedulerEngine.enqueue(new PendingEntry(ticket, task), cap);
        if (!admitted) {
            return false;
        }
        // Fix round 3: closes the residual admission window between resolving `generation` above
        // and this enqueue committing. This class holds no lock across the two, so a removal
        // racing exactly here can slip a ticket in after the generation it was built against has already been retired. Left
        // alone that ticket strands permanently in this profile: its queueDeadline is null (no
        // reaper ever collects it) and its generation is no longer active (engineReadiness never
        // selects it) — the same class of stranding C2 exists to prevent, under the same
        // redeploy-churn traffic. Re-checking here cannot close the window to zero (the check
        // itself is still non-atomic with a concurrent removal), but it turns an unbounded,
        // permanent strand into a bounded compensating removal: worst case, one ticket briefly
        // occupies a reservation before this catches it on the very next line.
        if (!generation.equals(capacityRegistry.activeGeneration(task.functionName()))) {
            schedulerEngine.remove(id);
            return false;
        }
        return true;
    }
}
