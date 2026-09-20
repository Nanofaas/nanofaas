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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The single admission/retry front door onto the composed {@link SchedulerEngine}, regardless
 * of how many {@code SchedulingStrategy} beans are on the classpath (Task 8, issue #208).
 *
 * <p>{@link InvocationEnqueuer#enqueue} and {@link RetryScheduler#enqueue} share one method, as
 * the interfaces already declare it identically and {@code QueueBackedEnqueuer} did the same for
 * the async-only profile: a fresh admission and a retry both become one ticket. Which admission
 * profile is active decides where that ticket goes:
 * <ul>
 *   <li>{@link AdmissionProfile#FUNCTION_QUEUE} — admits straight into the engine, exactly like
 *       the retired {@code QueueBackedEnqueuer}: no depth/wait-time gate beyond a soft
 *       per-function cap (see {@link PerFunctionDepth}).</li>
 *   <li>{@link AdmissionProfile#SYNC_QUEUE} — this bean's {@code enqueue} is only ever reached
 *       for a <em>retry</em> here (a fresh sync admission goes through
 *       {@link EngineSyncQueueGateway#enqueueOrThrow} directly, never through this class), and it
 *       delegates to the same gate a fresh admission would have used, exactly like the retired
 *       {@code SyncQueueInvocationEnqueuer}.</li>
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
    private final PerFunctionDepth perFunctionDepth;
    private final Clock clock;

    public EngineInvocationEnqueuer(ObjectProvider<SchedulerEngine> engine,
                                    DispatchCapacity capacityRegistry,
                                    LongSupplier sequence,
                                    AdmissionProfile profile,
                                    boolean asyncEnabled,
                                    ObjectProvider<EngineSyncQueueGateway> syncGateway,
                                    PerFunctionDepth perFunctionDepth) {
        this(engine, capacityRegistry, sequence, profile, asyncEnabled, syncGateway, perFunctionDepth,
                Clock.systemUTC());
    }

    EngineInvocationEnqueuer(ObjectProvider<SchedulerEngine> engine,
                             DispatchCapacity capacityRegistry,
                             LongSupplier sequence,
                             AdmissionProfile profile,
                             boolean asyncEnabled,
                             ObjectProvider<EngineSyncQueueGateway> syncGateway,
                             PerFunctionDepth perFunctionDepth,
                             Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.capacityRegistry = Objects.requireNonNull(capacityRegistry, "capacityRegistry must not be null");
        this.sequence = Objects.requireNonNull(sequence, "sequence must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.asyncEnabled = asyncEnabled;
        this.syncGateway = Objects.requireNonNull(syncGateway, "syncGateway must not be null");
        this.perFunctionDepth = Objects.requireNonNull(perFunctionDepth, "perFunctionDepth must not be null");
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

    private boolean admitDirect(InvocationTask task) {
        int cap = task.functionSpec() != null && task.functionSpec().queueSize() != null
                ? Math.max(1, task.functionSpec().queueSize())
                : Integer.MAX_VALUE;
        if (perFunctionDepth.get(task.functionName()) >= cap) {
            return false;
        }
        FunctionGeneration generation = capacityRegistry.activeGeneration(task.functionName());
        if (generation == null) {
            return false;
        }
        Instant now = clock.instant();
        TicketId id = new TicketId(task.executionId(), task.attempt());
        SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence.getAsLong(), now, now, null);
        boolean admitted = engine.getObject().enqueue(new PendingEntry(ticket, task));
        if (admitted) {
            perFunctionDepth.increment(task.functionName());
        }
        return admitted;
    }

    /**
     * Per-function count of tickets this enqueuer has admitted and the engine has not yet
     * settled, so the async profile's per-function queue-size cap ({@code FunctionSpec.queueSize})
     * is preserved even though {@code PendingWorkStore} enforces only one global cap.
     *
     * <p>ponytail: decremented when the engine hands a ticket to {@code EngineDispatch.submit},
     * not at the engine's own {@code finishSubmit}/{@code requeue} boundary (which
     * {@link it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport} does not
     * expose to this adapter). An input-backpressure requeue is therefore under-counted by one
     * for as long as it stays in retry — a narrow gap since input backpressure is rare and
     * self-resolving, not a systemic cap defeat. Revisit if it proves material.
     */
    public static final class PerFunctionDepth {
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        public void increment(String functionName) {
            counts.computeIfAbsent(functionName, ignored -> new AtomicInteger()).incrementAndGet();
        }

        public void decrement(String functionName) {
            counts.computeIfPresent(functionName, (name, count) -> count.decrementAndGet() <= 0 ? null : count);
        }

        public int get(String functionName) {
            AtomicInteger count = counts.get(functionName);
            return count == null ? 0 : count.get();
        }
    }
}
