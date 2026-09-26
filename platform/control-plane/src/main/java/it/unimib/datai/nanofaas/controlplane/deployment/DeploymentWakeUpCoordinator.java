package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Serializes generation-scoped deployment wake-ups with scale-downs for each function. */
public class DeploymentWakeUpCoordinator implements DeploymentWakeUpControl, AutoCloseable {
    private static final String GENERATION = "generation";
    private final FunctionCapacityRegistry generations;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<FunctionGeneration, FunctionState> functions = new ConcurrentHashMap<>();
    private final Set<FunctionGeneration> removedGenerations = new HashSet<>();
    private final AtomicLong leaseIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object stateLifecycle = new Object();

    @Autowired
    public DeploymentWakeUpCoordinator(
            FunctionCapacityRegistry generations,
            @Qualifier("deploymentWakeUpTimeoutScheduler") ScheduledExecutorService scheduler) {
        this(generations, scheduler, System::nanoTime);
    }

    DeploymentWakeUpCoordinator(FunctionCapacityRegistry generations,
                                ScheduledExecutorService scheduler,
                                LongSupplier nanoTime) {
        this.generations = Objects.requireNonNull(generations, "generations");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Installs a monotonic, generation-scoped wake-up lease and scales under the same lock.
     * The returned handle is the sole capability that releases this lease.
     */
    public WakeUpLease protectAndScaleUp(FunctionGeneration generation,
                                         ManagedDeploymentTarget target,
                                         long deadlineNanos,
                                         Runnable scaleUp) {
        Objects.requireNonNull(generation, GENERATION);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scaleUp, "scaleUp");
        if (!generation.functionName().equals(target.functionName())) {
            throw new IllegalArgumentException("wake-up generation and target must name the same function");
        }
        FunctionState state;
        synchronized (stateLifecycle) {
            pruneRemovalFences();
            if (closed.get() || removedGenerations.contains(generation) || !isCurrent(generation)) {
                throw new IllegalStateException(closed.get()
                        ? "DEPLOYMENT_WAKE_UP_CLOSED" : "DEPLOYMENT_WAKE_UP_REMOVED");
            }
            state = functions.computeIfAbsent(generation, ignored -> new FunctionState());
        }
        long leaseId = 0;
        state.mutationLock.lock();
        try {
            synchronized (state) {
                if (closed.get() || state.retired || functions.get(generation) != state || !isCurrent(generation)) {
                    retire(generation, state);
                    throw new IllegalStateException(closed.get()
                            ? "DEPLOYMENT_WAKE_UP_CLOSED" : "DEPLOYMENT_WAKE_UP_REMOVED");
                }
                cancel(state.expiryTask);
                leaseId = leaseIds.incrementAndGet();
                state.leaseId = leaseId;
                state.deadlineNanos = deadlineNanos;
                state.expiryTask = scheduleExpiry(generation, state, leaseId, deadlineNanos);
                state.runningCallbacks++;
            }
            try { // NOSONAR (java:S2093): the lease is returned to the caller, which closes it
                scaleUp.run();
                return new WakeUpLease(generation, state, leaseId);
            } finally {
                callbackFinished(generation, state);
            }
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            if (leaseId != 0) release(generation, state, leaseId);
            throw failure;
        } finally {
            state.mutationLock.unlock();
        }
    }

    /** Runs a downscale only when the active generation has no wake-up lease. */
    @Override
    public boolean scaleDownIfUnprotected(FunctionGeneration generation,
                                           ManagedDeploymentTarget target,
                                           BooleanSupplier scaleDown) {
        Objects.requireNonNull(generation, GENERATION);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scaleDown, "scaleDown");
        FunctionState state;
        synchronized (stateLifecycle) {
            pruneRemovalFences();
            if (!generation.functionName().equals(target.functionName())
                    || closed.get()
                    || removedGenerations.contains(generation)
                    || !isCurrent(generation)) {
                return false;
            }
            state = functions.computeIfAbsent(generation, ignored -> new FunctionState());
        }
        state.mutationLock.lock();
        try {
            synchronized (state) {
                if (closed.get() || state.retired || functions.get(generation) != state || !isCurrent(generation)) {
                    retire(generation, state);
                    return false;
                }
                expireIfDue(state, nanoTime.getAsLong());
                if (state.leaseId != 0) {
                    return false;
                }
                state.runningCallbacks++;
            }
            try {
                return scaleDown.getAsBoolean();
            } finally {
                callbackFinished(generation, state);
            }
        } finally {
            state.mutationLock.unlock();
        }
    }

    @Override
    public void removeFunctionState(String functionName) {
        synchronized (stateLifecycle) {
            pruneRemovalFences();
            FunctionGeneration activeGeneration = generations.activeGeneration(functionName);
            if (activeGeneration != null) {
                removedGenerations.add(activeGeneration);
            }
            for (var entry : new ArrayList<>(functions.entrySet())) {
                if (entry.getKey().functionName().equals(functionName)) {
                    retire(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /** Reopens only the exact still-current generation after a failed removal is rolled back. */
    public void restoreFunctionState(FunctionGeneration generation) {
        Objects.requireNonNull(generation, GENERATION);
        synchronized (stateLifecycle) {
            pruneRemovalFences();
            if (isCurrent(generation)) {
                removedGenerations.remove(generation);
            }
        }
    }

    int ownedStateCount() {
        return functions.size();
    }

    int ownedLeaseCount() {
        int leases = 0;
        for (FunctionState state : functions.values()) {
            synchronized (state) {
                if (state.leaseId != 0) {
                    leases++;
                }
            }
        }
        return leases;
    }

    @Override
    public void close() {
        synchronized (stateLifecycle) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (var entry : new ArrayList<>(functions.entrySet())) {
                retire(entry.getKey(), entry.getValue());
            }
        }
    }

    private ScheduledFuture<?> scheduleExpiry(FunctionGeneration generation,
                                              FunctionState state,
                                              long leaseId,
                                              long deadlineNanos) {
        long delay = Math.max(0, deadlineNanos - nanoTime.getAsLong());
        return scheduler.schedule(
                () -> expire(generation, state, leaseId, deadlineNanos), delay, TimeUnit.NANOSECONDS);
    }

    private void expire(FunctionGeneration generation,
                        FunctionState state,
                        long leaseId,
                        long deadlineNanos) {
        synchronized (state) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            if (functions.get(generation) != state
                    || state.retired
                    || state.leaseId != leaseId
                    || state.deadlineNanos != deadlineNanos) {
                return;
            }
            long now = nanoTime.getAsLong();
            if (now < deadlineNanos) {
                ScheduledFuture<?> previous = state.expiryTask;
                state.expiryTask = scheduleExpiry(generation, state, leaseId, deadlineNanos);
                cancel(previous);
                return;
            }
            state.leaseId = 0;
            state.deadlineNanos = 0;
            ScheduledFuture<?> expiry = state.expiryTask;
            state.expiryTask = null;
            cancel(expiry);
        }
    }

    private void expireIfDue(FunctionState state, long now) {
        if (state.leaseId != 0 && now >= state.deadlineNanos) {
            state.leaseId = 0;
            state.deadlineNanos = 0;
            ScheduledFuture<?> expiry = state.expiryTask;
            state.expiryTask = null;
            cancel(expiry);
        }
    }

    private void release(FunctionGeneration generation, FunctionState state, long leaseId) {
        synchronized (state) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            if (state.leaseId != leaseId) {
                return;
            }
            state.leaseId = 0;
            state.deadlineNanos = 0;
            ScheduledFuture<?> expiry = state.expiryTask;
            state.expiryTask = null;
            cancel(expiry);
            drainIfRetired(generation, state);
        }
    }

    private void retire(FunctionGeneration generation, FunctionState state) {
        synchronized (state) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            state.retired = true;
            state.leaseId = 0;
            state.deadlineNanos = 0;
            ScheduledFuture<?> expiry = state.expiryTask;
            state.expiryTask = null;
            cancel(expiry);
            drainIfRetired(generation, state);
        }
    }

    private void callbackFinished(FunctionGeneration generation, FunctionState state) {
        synchronized (state) { // NOSONAR (java:S2445): this object is its own monitor by design; every path locks the same instance
            state.runningCallbacks--;
            drainIfRetired(generation, state);
        }
    }

    private void drainIfRetired(FunctionGeneration generation, FunctionState state) {
        if (state.retired && state.runningCallbacks == 0) {
            functions.remove(generation, state);
        }
    }

    private boolean isCurrent(FunctionGeneration generation) {
        return generation.equals(generations.activeGeneration(generation.functionName()));
    }

    /**
     * Removal fences remain until capacity ownership advances. This bounds them to generations that
     * can still be current while old callback attribution drains independently through {@link #functions}.
     */
    private void pruneRemovalFences() {
        removedGenerations.removeIf(generation -> !isCurrent(generation));
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    public final class WakeUpLease implements AutoCloseable {
        private final FunctionGeneration generation;
        private final FunctionState state;
        private final long leaseId;
        private final AtomicBoolean released = new AtomicBoolean();

        private WakeUpLease(FunctionGeneration generation, FunctionState state, long leaseId) {
            this.generation = generation;
            this.state = state;
            this.leaseId = leaseId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release(generation, state, leaseId);
            }
        }
    }

    private static final class FunctionState {
        private final ReentrantLock mutationLock = new ReentrantLock();
        private long leaseId;
        private long deadlineNanos;
        private ScheduledFuture<?> expiryTask;
        private int runningCallbacks;
        private boolean retired;
    }
}
