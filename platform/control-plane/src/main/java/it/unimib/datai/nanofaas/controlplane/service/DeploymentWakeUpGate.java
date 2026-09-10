package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

@Service
public class DeploymentWakeUpGate implements FunctionRegistrationListener, AutoCloseable {

    private static final ScheduledThreadPoolExecutor FALLBACK_TIMEOUT_SCHEDULER = fallbackScheduler();

    private final FunctionRegistry registry;
    private final ManagedDeploymentCoordinator coordinator;
    private final FunctionCapacityRegistry generations;
    private final Duration timeout;
    private final Duration pollInterval;
    private final Duration readyObservationMaxAge;
    private final Executor executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final DeploymentWakeUpCoordinator wakeUpCoordinator;
    private final InstantSource clock;
    private final LongSupplier nanoTime;
    private final boolean standaloneGenerations;
    private final ConcurrentMap<FunctionGeneration, WakeUp> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public DeploymentWakeUpGate(FunctionRegistry registry,
                                ManagedDeploymentCoordinator coordinator,
                                FunctionCapacityRegistry generations,
                                DeploymentWakeUpProperties properties,
                                @Qualifier("deploymentWakeUpExecutor") Executor executor,
                                @Qualifier("deploymentWakeUpTimeoutScheduler") ScheduledExecutorService timeoutScheduler,
                                DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this(registry, coordinator, generations, properties, executor, timeoutScheduler, wakeUpCoordinator,
                InstantSource.system(), System::nanoTime, false);
    }

    public DeploymentWakeUpGate(FunctionRegistry registry,
                                ManagedDeploymentCoordinator coordinator,
                                Duration timeout,
                                Duration pollInterval) {
        this(registry, coordinator, standaloneGenerations(),
                new DeploymentWakeUpProperties(timeout, pollInterval), Runnable::run,
                FALLBACK_TIMEOUT_SCHEDULER, new DeploymentWakeUpCoordinator(),
                InstantSource.system(), System::nanoTime, true);
    }

    DeploymentWakeUpGate(FunctionRegistry registry,
                         ManagedDeploymentCoordinator coordinator,
                         Duration timeout,
                         Duration pollInterval,
                         Executor executor) {
        this(registry, coordinator, standaloneGenerations(),
                new DeploymentWakeUpProperties(timeout, pollInterval), executor,
                FALLBACK_TIMEOUT_SCHEDULER, new DeploymentWakeUpCoordinator(),
                InstantSource.system(), System::nanoTime, true);
    }

    public DeploymentWakeUpGate(FunctionRegistry registry,
                                ManagedDeploymentCoordinator coordinator,
                                Duration timeout,
                                Duration pollInterval,
                                Executor executor,
                                DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this(registry, coordinator, standaloneGenerations(),
                new DeploymentWakeUpProperties(timeout, pollInterval), executor,
                FALLBACK_TIMEOUT_SCHEDULER, wakeUpCoordinator,
                InstantSource.system(), System::nanoTime, true);
    }

    DeploymentWakeUpGate(FunctionRegistry registry,
                         ManagedDeploymentCoordinator coordinator,
                         Duration timeout,
                         Duration pollInterval,
                         Executor executor,
                         ScheduledExecutorService timeoutScheduler,
                         DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this(registry, coordinator, standaloneGenerations(),
                new DeploymentWakeUpProperties(timeout, pollInterval), executor,
                timeoutScheduler, wakeUpCoordinator, InstantSource.system(), System::nanoTime, true);
    }

    DeploymentWakeUpGate(FunctionRegistry registry,
                         ManagedDeploymentCoordinator coordinator,
                         FunctionCapacityRegistry generations,
                         DeploymentWakeUpProperties properties,
                         Executor executor,
                         ScheduledExecutorService timeoutScheduler,
                         DeploymentWakeUpCoordinator wakeUpCoordinator,
                         InstantSource clock,
                         LongSupplier nanoTime) {
        this(registry, coordinator, generations, properties, executor, timeoutScheduler, wakeUpCoordinator,
                clock, nanoTime, false);
    }

    private DeploymentWakeUpGate(FunctionRegistry registry,
                                 ManagedDeploymentCoordinator coordinator,
                                 FunctionCapacityRegistry generations,
                                 DeploymentWakeUpProperties properties,
                                 Executor executor,
                                 ScheduledExecutorService timeoutScheduler,
                                 DeploymentWakeUpCoordinator wakeUpCoordinator,
                                 InstantSource clock,
                                 LongSupplier nanoTime,
                                 boolean standaloneGenerations) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.timeout = properties.timeout();
        this.pollInterval = properties.pollInterval();
        this.readyObservationMaxAge = properties.readyObservationMaxAge();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.wakeUpCoordinator = Objects.requireNonNull(wakeUpCoordinator, "wakeUpCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.standaloneGenerations = standaloneGenerations;
    }

    public CompletableFuture<Void> ensureReady(InvocationTask task) {
        if (closed.get()) {
            return failed("DEPLOYMENT_WAKE_UP_CLOSED");
        }
        Optional<RegisteredFunction> registered = registry.getRegistered(task.functionName());
        if (registered.isEmpty()) {
            return isEligible(task.functionSpec()) ? unavailableTarget() : CompletableFuture.completedFuture(null);
        }
        RegisteredFunction function = registered.get();
        if (!isEligible(function)) {
            return CompletableFuture.completedFuture(null);
        }
        String backend = function.deploymentMetadata().deploymentBackend();
        if (backend == null || backend.isBlank()) {
            return unavailableTarget();
        }
        FunctionGeneration generation = activeGeneration(function);
        if (generation == null) {
            return unavailableTarget();
        }
        ManagedDeploymentTarget target = new ManagedDeploymentTarget(function.name(), backend);
        ReplicaObservation observation = coordinator.observeReplicaStatus(target);
        if (isReadyWithinPolicy(observation, clock.instant())) {
            if (closed.get()) {
                return failed("DEPLOYMENT_WAKE_UP_CLOSED");
            }
            if (!isActive(generation)) {
                return failed("DEPLOYMENT_WAKE_UP_REMOVED");
            }
            return CompletableFuture.completedFuture(null);
        }
        if (closed.get() || !isActive(generation)) {
            return failed(closed.get() ? "DEPLOYMENT_WAKE_UP_CLOSED" : "DEPLOYMENT_WAKE_UP_REMOVED");
        }

        WakeUp candidate = new WakeUp(generation, target);
        WakeUp owner = inFlight.putIfAbsent(generation, candidate);
        if (owner == null) {
            owner = candidate;
            owner.start();
        }
        return owner.callerView();
    }

    @Override
    public void onRegister(FunctionSpec spec) {
        // P07's capacity listener owns generation creation. A registration cannot revive old work.
    }

    @Override
    public void onRemove(String functionName) {
        for (WakeUp wakeUp : new ArrayList<>(inFlight.values())) {
            if (wakeUp.generation.functionName().equals(functionName)) {
                wakeUp.fail("DEPLOYMENT_WAKE_UP_REMOVED");
            }
        }
        wakeUpCoordinator.removeFunctionState(functionName);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (WakeUp wakeUp : new ArrayList<>(inFlight.values())) {
            wakeUp.fail("DEPLOYMENT_WAKE_UP_CLOSED");
        }
    }

    int ownedWakeUpCount() {
        return inFlight.size();
    }

    private FunctionGeneration activeGeneration(RegisteredFunction function) {
        FunctionGeneration generation = generations.activeGeneration(function.name());
        if (generation == null && standaloneGenerations) {
            generations.register(function.name(), function.spec().concurrency());
            generation = generations.activeGeneration(function.name());
        }
        return generation;
    }

    private boolean isReadyWithinPolicy(ReplicaObservation observation, Instant now) {
        return observation instanceof ReplicaObservation.Available available
                && available.state() == ReplicaObservation.State.FRESH
                && available.status().readyReplicas() > 0
                && !available.observedAt().isBefore(now.minus(readyObservationMaxAge));
    }

    private boolean isCurrent(FunctionGeneration generation) {
        return generation.equals(generations.activeGeneration(generation.functionName()));
    }

    private boolean isActive(FunctionGeneration generation) {
        return isCurrent(generation) && registry.getRegistered(generation.functionName()).isPresent();
    }

    private void submit(Runnable action, WakeUp owner) {
        try {
            executor.execute(action);
        } catch (RuntimeException | Error failure) {
            owner.completeExceptionally(failure);
        }
    }

    private static boolean isEligible(RegisteredFunction function) {
        DeploymentMetadata metadata = function.deploymentMetadata();
        return metadata.effectiveExecutionMode() == ExecutionMode.DEPLOYMENT && isEligible(function.spec());
    }

    private static boolean isEligible(FunctionSpec spec) {
        ScalingConfig scaling = spec.scalingConfig();
        return spec.executionMode() == ExecutionMode.DEPLOYMENT
                && scaling != null
                && scaling.strategy() == ScalingStrategy.INTERNAL
                && scaling.minReplicas() == 0;
    }

    private static CompletableFuture<Void> unavailableTarget() {
        return failed("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
    }

    private static CompletableFuture<Void> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    private static FunctionCapacityRegistry standaloneGenerations() {
        return new FunctionCapacityRegistry();
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

    private final class WakeUp {
        private final FunctionGeneration generation;
        private final ManagedDeploymentTarget target;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeoutTask;
        private volatile ScheduledFuture<?> pollTask;
        private volatile DeploymentWakeUpCoordinator.WakeUpLease wakeUpLease;

        private WakeUp(FunctionGeneration generation, ManagedDeploymentTarget target) {
            this.generation = generation;
            this.target = target;
            result.whenComplete((ignored, failure) -> cleanup());
        }

        private void start() {
            long deadline = nanoTime.getAsLong() + timeout.toNanos();
            try {
                installTimeout(timeoutScheduler.schedule(
                        () -> fail("DEPLOYMENT_WAKE_UP_TIMEOUT"), timeout.toNanos(), TimeUnit.NANOSECONDS));
                submit(() -> readAndWake(deadline), this);
            } catch (RuntimeException | Error failure) {
                completeExceptionally(failure);
            }
        }

        private CompletableFuture<Void> callerView() {
            CompletableFuture<Void> view = new CompletableFuture<>();
            result.whenComplete((ignored, failure) -> {
                if (failure != null) view.completeExceptionally(failure);
                else view.complete(null);
            });
            return view;
        }

        private void readAndWake(long deadline) {
            if (!canContinue()) return;
            try {
                ReplicaStatus status = coordinator.getFreshReplicaStatus(target);
                if (!canContinue()) return;
                if (status.readyReplicas() > 0) {
                    result.complete(null);
                    return;
                }
                DeploymentWakeUpCoordinator.WakeUpLease lease = wakeUpCoordinator.protectAndScaleUp(
                        generation, target, deadline, () -> {
                    if (status.desiredReplicas() == 0 && canContinue()) {
                        coordinator.setReplicas(target, 1);
                    }
                });
                installLease(lease);
                schedulePoll(deadline);
            } catch (RuntimeException | Error failure) {
                completeExceptionally(failure);
            }
        }

        private void poll(long deadline) {
            if (!canContinue()) return;
            try {
                ReplicaStatus status = coordinator.getFreshReplicaStatus(target);
                if (!canContinue()) return;
                if (status.readyReplicas() > 0) result.complete(null);
                else schedulePoll(deadline);
            } catch (RuntimeException | Error failure) {
                completeExceptionally(failure);
            }
        }

        private void schedulePoll(long deadline) {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                fail("DEPLOYMENT_WAKE_UP_TIMEOUT");
                return;
            }
            try {
                ScheduledFuture<?> scheduled = timeoutScheduler.schedule(
                        () -> submit(() -> poll(deadline), this),
                        Math.min(pollInterval.toNanos(), remaining), TimeUnit.NANOSECONDS);
                installPoll(scheduled);
            } catch (RuntimeException | Error failure) {
                completeExceptionally(failure);
            }
        }

        private boolean canContinue() {
            if (result.isDone()) return false;
            if (closed.get()) {
                fail("DEPLOYMENT_WAKE_UP_CLOSED");
                return false;
            }
            if (!isActive(generation)) {
                fail("DEPLOYMENT_WAKE_UP_REMOVED");
                return false;
            }
            return true;
        }

        private void installTimeout(ScheduledFuture<?> scheduled) {
            timeoutTask = scheduled;
            if (result.isDone()) scheduled.cancel(false);
        }

        private void installPoll(ScheduledFuture<?> scheduled) {
            ScheduledFuture<?> previous = pollTask;
            pollTask = scheduled;
            if (previous != null) previous.cancel(false);
            if (result.isDone()) scheduled.cancel(false);
        }

        private void installLease(DeploymentWakeUpCoordinator.WakeUpLease lease) {
            wakeUpLease = lease;
            if (cleaned.get()) lease.close();
        }

        private void fail(String message) {
            result.completeExceptionally(new IllegalStateException(message));
        }

        private void completeExceptionally(Throwable failure) {
            result.completeExceptionally(failure);
        }

        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) return;
            inFlight.remove(generation, this);
            ScheduledFuture<?> timeout = timeoutTask;
            if (timeout != null) timeout.cancel(false);
            ScheduledFuture<?> poll = pollTask;
            if (poll != null) poll.cancel(false);
            DeploymentWakeUpCoordinator.WakeUpLease lease = wakeUpLease;
            if (lease != null) lease.close();
        }
    }
}
