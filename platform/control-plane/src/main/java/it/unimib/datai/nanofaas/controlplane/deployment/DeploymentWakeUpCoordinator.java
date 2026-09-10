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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Serializes generation-scoped deployment wake-ups with scale-downs for each function. */
@Service
public class DeploymentWakeUpCoordinator implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor FALLBACK_SCHEDULER = fallbackScheduler();

    private final FunctionCapacityRegistry generations;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final boolean standaloneGenerations;
    private final ConcurrentMap<FunctionGeneration, FunctionState> functions = new ConcurrentHashMap<>();
    private final AtomicLong leaseIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DeploymentWakeUpCoordinator() {
        this(new FunctionCapacityRegistry(), FALLBACK_SCHEDULER, System::nanoTime, true);
    }

    @Autowired
    public DeploymentWakeUpCoordinator(
            FunctionCapacityRegistry generations,
            @Qualifier("deploymentWakeUpTimeoutScheduler") ScheduledExecutorService scheduler) {
        this(generations, scheduler, System::nanoTime, false);
    }

    DeploymentWakeUpCoordinator(FunctionCapacityRegistry generations,
                                ScheduledExecutorService scheduler,
                                LongSupplier nanoTime) {
        this(generations, scheduler, nanoTime, false);
    }

    private DeploymentWakeUpCoordinator(FunctionCapacityRegistry generations,
                                        ScheduledExecutorService scheduler,
                                        LongSupplier nanoTime,
                                        boolean standaloneGenerations) {
        this.generations = Objects.requireNonNull(generations, "generations");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.standaloneGenerations = standaloneGenerations;
    }

    /**
     * Compatibility entry point for tests and embedded users. Production callers should carry the
     * generation they acquired from the P07 authority.
     */
    public WakeUpLease protectAndScaleUp(ManagedDeploymentTarget target, long deadlineNanos, Runnable scaleUp) {
        FunctionGeneration generation = activeGeneration(target.functionName());
        if (generation == null) {
            throw new IllegalStateException("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
        }
        return protectAndScaleUp(generation, target, deadlineNanos, scaleUp);
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
        synchronized (state) {
            if (closed.get() || functions.get(generation) != state || !isCurrent(generation)) {
                functions.remove(generation, state);
                throw new IllegalStateException(closed.get()
                        ? "DEPLOYMENT_WAKE_UP_CLOSED" : "DEPLOYMENT_WAKE_UP_REMOVED");
            }
            cancel(state.expiryTask);
            long leaseId = leaseIds.incrementAndGet();
            state.leaseId = leaseId;
            state.deadlineNanos = deadlineNanos;
            try {
                state.expiryTask = scheduleExpiry(generation, state, leaseId, deadlineNanos);
                scaleUp.run();
                return new WakeUpLease(generation, state, leaseId);
            } catch (RuntimeException | Error failure) {
                release(generation, state, leaseId);
                throw failure;
            }
        }
    }

    /** Runs a downscale only when the active generation has no wake-up lease. */
    public boolean scaleDownIfUnprotected(ManagedDeploymentTarget target, Runnable scaleDown) {
        while (!closed.get()) {
            FunctionGeneration generation = activeGeneration(target.functionName());
            if (generation == null) {
                scaleDown.run();
                return true;
            }
            FunctionState state = functions.computeIfAbsent(generation, ignored -> new FunctionState());
            synchronized (state) {
                if (closed.get()) {
                    functions.remove(generation, state);
                    return false;
                }
                if (functions.get(generation) != state || !isCurrent(generation)) {
                    continue;
                }
                expireIfDue(state, nanoTime.getAsLong());
                if (state.leaseId != 0) {
                    return false;
                }
                scaleDown.run();
                return true;
            }
        }
        return false;
    }

    boolean isScaleDownProtected(String functionName) {
        FunctionGeneration generation = activeGeneration(functionName);
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
            if (entry.getKey().functionName().equals(functionName)
                    && functions.remove(entry.getKey(), entry.getValue())) {
                clear(entry.getValue());
            }
        }
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
            if (functions.remove(entry.getKey(), entry.getValue())) {
                clear(entry.getValue());
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
        synchronized (state) {
            if (functions.get(generation) != state
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
            if (functions.get(generation) != state || state.leaseId != leaseId) {
                return;
            }
            state.leaseId = 0;
            state.deadlineNanos = 0;
            ScheduledFuture<?> expiry = state.expiryTask;
            state.expiryTask = null;
            cancel(expiry);
        }
    }

    private static void clear(FunctionState state) {
        synchronized (state) {
            state.leaseId = 0;
            state.deadlineNanos = 0;
            ScheduledFuture<?> expiry = state.expiryTask;
            state.expiryTask = null;
            cancel(expiry);
        }
    }

    private FunctionGeneration activeGeneration(String functionName) {
        FunctionGeneration generation = generations.activeGeneration(functionName);
        if (generation == null && standaloneGenerations && !closed.get()) {
            generations.register(functionName, 1);
            generation = generations.activeGeneration(functionName);
        }
        return generation;
    }

    private boolean isCurrent(FunctionGeneration generation) {
        if (standaloneGenerations) {
            return !closed.get();
        }
        return generation.equals(generations.activeGeneration(generation.functionName()));
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static ScheduledThreadPoolExecutor fallbackScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "deployment-wakeup-timeout-");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
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
        private long leaseId;
        private long deadlineNanos;
        private ScheduledFuture<?> expiryTask;
    }
}
