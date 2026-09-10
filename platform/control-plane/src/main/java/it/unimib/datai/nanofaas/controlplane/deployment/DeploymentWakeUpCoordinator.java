package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Objects;
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
@Service
public class DeploymentWakeUpCoordinator implements AutoCloseable {
    private final FunctionCapacityRegistry generations;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<FunctionGeneration, FunctionState> functions = new ConcurrentHashMap<>();
    private final AtomicLong leaseIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

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
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scaleUp, "scaleUp");
        if (!generation.functionName().equals(target.functionName())) {
            throw new IllegalArgumentException("wake-up generation and target must name the same function");
        }
        if (closed.get() || !isCurrent(generation)) {
            throw new IllegalStateException(closed.get()
                    ? "DEPLOYMENT_WAKE_UP_CLOSED" : "DEPLOYMENT_WAKE_UP_REMOVED");
        }

        FunctionState state = functions.computeIfAbsent(generation, ignored -> new FunctionState());
        state.mutationLock.lock();
        long leaseId = 0;
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
            try {
                scaleUp.run();
                return new WakeUpLease(generation, state, leaseId);
            } finally {
                callbackFinished(generation, state);
            }
        } catch (RuntimeException | Error failure) {
            if (leaseId != 0) release(generation, state, leaseId);
            throw failure;
        } finally {
            state.mutationLock.unlock();
        }
    }

    /** Runs a downscale only when the active generation has no wake-up lease. */
    public boolean scaleDownIfUnprotected(FunctionGeneration generation,
                                           ManagedDeploymentTarget target,
                                           BooleanSupplier scaleDown) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scaleDown, "scaleDown");
        if (!generation.functionName().equals(target.functionName()) || closed.get() || !isCurrent(generation)) {
            return false;
        }
        FunctionState state = functions.computeIfAbsent(generation, ignored -> new FunctionState());
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

    boolean isScaleDownProtected(String functionName) {
        FunctionGeneration generation = generations.activeGeneration(functionName);
        if (generation == null) {
            return false;
        }
        FunctionState state = functions.get(generation);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (functions.get(generation) != state || !isCurrent(generation)) {
                return false;
            }
            expireIfDue(state, nanoTime.getAsLong());
            return state.leaseId != 0;
        }
    }

    public void removeFunctionState(String functionName) {
        for (var entry : new ArrayList<>(functions.entrySet())) {
            if (entry.getKey().functionName().equals(functionName)) {
                retire(entry.getKey(), entry.getValue());
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (var entry : new ArrayList<>(functions.entrySet())) {
            retire(entry.getKey(), entry.getValue());
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
        synchronized (state) {
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
        synchronized (state) {
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
        synchronized (state) {
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
        synchronized (state) {
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
