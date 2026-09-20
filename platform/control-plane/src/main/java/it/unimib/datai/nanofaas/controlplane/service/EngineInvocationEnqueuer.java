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

    @Override
    public boolean isQueueFull(String functionName) {
        // Advisory only (enqueue remains authoritative). Answerable only once this function has
        // been admitted at least once (PerFunctionDepth then knows its cap) — the retired
        // QueueManager.isQueueFull had the same limit, since it too could only report on a
        // function it already tracked a FunctionQueueState for.
        return profile == AdmissionProfile.FUNCTION_QUEUE && perFunctionDepth.isFull(functionName);
    }

    private boolean admitDirect(InvocationTask task) {
        int cap = task.functionSpec() != null && task.functionSpec().queueSize() != null
                ? Math.max(1, task.functionSpec().queueSize())
                : Integer.MAX_VALUE;
        if (!perFunctionDepth.tryAcquire(task.functionName(), cap)) {
            return false;
        }
        FunctionGeneration generation = capacityRegistry.activeGeneration(task.functionName());
        if (generation == null) {
            perFunctionDepth.release(task.functionName());
            return false;
        }
        Instant now = clock.instant();
        TicketId id = new TicketId(task.executionId(), task.attempt());
        SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence.getAsLong(), now, now, null);
        SchedulerEngine schedulerEngine = engine.getObject();
        boolean admitted = schedulerEngine.enqueue(new PendingEntry(ticket, task));
        if (!admitted) {
            perFunctionDepth.release(task.functionName());
            return false;
        }
        // Fix round 3: closes the residual admission window between resolving `generation` above
        // and this enqueue committing. The retired QueueManager admitted under its own capacity
        // lock, so a concurrent removal could never observe a still-active generation and let a
        // ticket through; this class has no such lock, so a removal racing exactly here can slip
        // a ticket in after the generation it was built against has already been retired. Left
        // alone that ticket strands permanently in this profile: its queueDeadline is null (no
        // reaper ever collects it) and its generation is no longer active (engineReadiness never
        // selects it) — the same class of stranding C2 exists to prevent, under the same
        // redeploy-churn traffic. Re-checking here cannot close the window to zero (the check
        // itself is still non-atomic with a concurrent removal), but it turns an unbounded,
        // permanent strand into a bounded compensating removal: worst case, one ticket briefly
        // occupies a reservation before this catches it on the very next line.
        //
        // Deliberately no extra `perFunctionDepth.release` here: `SchedulerEngine.remove(id)`
        // already releases through `EngineDispatch.removed` -> `settle`/`release` on the one path
        // where this ticket is actually still pending and gets pulled back out (see
        // `EngineTransport.removed`, `SchedulerConfiguration`). Adding a second release on this
        // branch would double-release against that path. If the ticket is no longer pending by
        // the time `remove` runs — already claimed/submitting, or already reaped by a concurrent
        // `removeAllFor` — then `remove` is a no-op here and whichever path actually settled that
        // ticket (dispatch's own `submit`/`expired` settlement, or that concurrent removal) is
        // the one that already released, or will release, its depth slot exactly once.
        if (!generation.equals(capacityRegistry.activeGeneration(task.functionName()))) {
            schedulerEngine.remove(id);
            return false;
        }
        return true;
    }

    /**
     * Per-function count of tickets this enqueuer has admitted and the engine has not yet
     * settled, so the async profile's per-function queue-size cap ({@code FunctionSpec.queueSize})
     * is preserved even though {@code PendingWorkStore} enforces only one global cap.
     *
     * <p>Fix round 2: {@link #tryAcquire} and {@link #release} both mutate {@code counts}
     * exclusively through {@link ConcurrentHashMap#compute}/{@code computeIfPresent}, so the
     * cap-check-and-increment and the decrement-or-unmap are each one atomic operation on the
     * map's own per-key locking — not a separate read, a CAS loop and an independent unmap that
     * could interleave. The previous shape (an external CAS loop over a value fetched from
     * {@code computeIfAbsent}, with {@code release} unmapping the same entry independently) had
     * exactly that interleaving: a release that unmaps the counter between a concurrent
     * {@code tryAcquire}'s read and its CAS let the CAS succeed against an orphaned
     * {@code AtomicInteger}, silently loosening the cap forever. There is no such window here:
     * both methods only ever touch the map's own atomic per-key operations.
     *
     * <p>{@link #release} is called by {@code EngineTransport} exactly at the engine's own
     * {@code finishSubmit}/{@code requeue} decision (whether {@code EngineDispatch.submit}
     * threw {@code InvocationQuotaExceededException}, matching {@code SchedulerEngine.submit}'s
     * own branch), and by {@code admitDirect} itself when a slot it reserved does not end up
     * used (generation gone, or the engine's own store rejected it). A backpressure requeue is
     * therefore not released — and not double-released either, since it was never released for
     * that attempt in the first place.
     *
     * <p>{@link #forget} is called on function removal (the same hook C2 added for the engine's
     * own drain), so neither map retains a function's cap/count forever once it is never
     * re-registered.
     */
    public static final class PerFunctionDepth {
        private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Integer> caps = new ConcurrentHashMap<>();

        /** Atomically reserves one slot iff doing so would not exceed {@code cap}. */
        public boolean tryAcquire(String functionName, int cap) {
            caps.put(functionName, cap);
            boolean[] acquired = {false};
            counts.compute(functionName, (name, current) -> {
                int value = current == null ? 0 : current;
                if (value >= cap) {
                    acquired[0] = false;
                    return current;
                }
                acquired[0] = true;
                return value + 1;
            });
            return acquired[0];
        }

        public void release(String functionName) {
            counts.computeIfPresent(functionName, (name, count) -> count <= 1 ? null : count - 1);
        }

        public int get(String functionName) {
            Integer count = counts.get(functionName);
            return count == null ? 0 : count;
        }

        /** {@code false} for a function never seen by {@link #tryAcquire} — matches the SPI's
         * own {@code isQueueFull} default. */
        public boolean isFull(String functionName) {
            Integer cap = caps.get(functionName);
            return cap != null && get(functionName) >= cap;
        }

        /** Drops this function's tracked cap and count entirely; called on removal. */
        public void forget(String functionName) {
            counts.remove(functionName);
            caps.remove(functionName);
        }
    }
}
