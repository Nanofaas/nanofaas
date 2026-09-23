package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
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
import org.springframework.beans.factory.annotation.Qualifier;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public class DeploymentWakeUpGate implements DeploymentReadiness, FunctionRegistrationListener, AutoCloseable {
    private static final String WAKE_UP_CLOSED = "DEPLOYMENT_WAKE_UP_CLOSED";
    private static final String WAKE_UP_REMOVED = "DEPLOYMENT_WAKE_UP_REMOVED";

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
    private final ConcurrentMap<FunctionGeneration, WakeUp> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object ownerLifecycle = new Object();

    public DeploymentWakeUpGate(FunctionRegistry registry,
                                ManagedDeploymentCoordinator coordinator,
                                FunctionCapacityRegistry generations,
                                DeploymentWakeUpProperties properties,
                                @Qualifier("deploymentWakeUpExecutor") Executor executor,
                                @Qualifier("deploymentWakeUpTimeoutScheduler") ScheduledExecutorService timeoutScheduler,
                                DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this(registry, coordinator, generations, properties, executor, timeoutScheduler, wakeUpCoordinator,
                InstantSource.system(), System::nanoTime);
    }

    DeploymentWakeUpGate(FunctionRegistry registry, // NOSONAR (java:S107): composition constructor; each argument is an injected collaborator or limit
                         ManagedDeploymentCoordinator coordinator,
                         FunctionCapacityRegistry generations,
                         DeploymentWakeUpProperties properties,
                         Executor executor,
                         ScheduledExecutorService timeoutScheduler,
                         DeploymentWakeUpCoordinator wakeUpCoordinator,
                         InstantSource clock,
                         LongSupplier nanoTime) {
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
    }

    @Override
    public CompletableFuture<Void> ensureReady(InvocationTask task) {
        if (closed.get()) {
            return failed(WAKE_UP_CLOSED);
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
                return failed(WAKE_UP_CLOSED);
            }
            if (!isActive(generation)) {
                return failed(WAKE_UP_REMOVED);
            }
            return CompletableFuture.completedFuture(null);
        }
        if (closed.get() || !isActive(generation)) {
            return failed(closed.get() ? WAKE_UP_CLOSED : WAKE_UP_REMOVED);
        }

        WakeUp owner;
        boolean startOwner = false;
        synchronized (ownerLifecycle) {
            if (closed.get() || !isActive(generation)) {
                return failed(closed.get() ? WAKE_UP_CLOSED : WAKE_UP_REMOVED);
            }
            WakeUp candidate = new WakeUp(generation, target);
            owner = inFlight.putIfAbsent(generation, candidate);
            if (owner == null) {
                owner = candidate;
                startOwner = true;
            }
        }
        if (startOwner) {
            owner.start();
        }
        return owner.callerView();
    }

    @Override
    public void onRegister(FunctionSpec spec) {
        FunctionGeneration generation = generations.activeGeneration(spec.name());
        if (generation != null) {
            wakeUpCoordinator.restoreFunctionState(generation);
        }
    }

    @Override
    public void onRemove(String functionName) {
        synchronized (ownerLifecycle) {
            for (WakeUp wakeUp : new ArrayList<>(inFlight.values())) {
                if (wakeUp.generation.functionName().equals(functionName)) {
                    wakeUp.fail(WAKE_UP_REMOVED);
                }
            }
        }
        wakeUpCoordinator.removeFunctionState(functionName);
    }

    @Override
    public void close() {
        synchronized (ownerLifecycle) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (WakeUp wakeUp : new ArrayList<>(inFlight.values())) {
                wakeUp.fail(WAKE_UP_CLOSED);
            }
        }
    }

    int ownedWakeUpCount() {
        return inFlight.size();
    }

    private FunctionGeneration activeGeneration(RegisteredFunction function) {
        return coordinator.generationOf(function);
    }

    private boolean isReadyWithinPolicy(ReplicaObservation observation, Instant now) {
        return observation instanceof ReplicaObservation.Available(var status, var state, var observedAt)
                && state == ReplicaObservation.State.FRESH
                && status.readyReplicas() > 0
                && !observedAt.isBefore(now.minus(readyObservationMaxAge));
    }

    private boolean isCurrent(FunctionGeneration generation) {
        return generation.equals(generations.activeGeneration(generation.functionName()));
    }

    private boolean isActive(FunctionGeneration generation) { // NOSONAR (java:S3398): also used by the enclosing class
        return isCurrent(generation) && registry.getRegistered(generation.functionName()).isPresent();
    }

    private void submit(Runnable action, WakeUp owner) {
        if (!owner.callbackStarted()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    action.run();
                } finally {
                    owner.callbackFinished();
                }
            });
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            owner.callbackFinished();
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

    @SuppressWarnings("FutureReturnValueIgnored") // result owns both lifecycle callbacks.
    private final class WakeUp {
        private final FunctionGeneration generation;
        private final ManagedDeploymentTarget target;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private ScheduledFuture<?> timeoutTask;
        private ScheduledFuture<?> pollTask;
        private DeploymentWakeUpCoordinator.WakeUpLease wakeUpLease;
        private int runningCallbacks;
        private long pollVersion;
        private boolean schedulingPoll;
        private boolean pollRanWhileScheduling;
        private boolean retired;
        private boolean drained;

        private WakeUp(FunctionGeneration generation, ManagedDeploymentTarget target) {
            this.generation = generation;
            this.target = target;
            result.whenComplete((ignored, failure) -> retire());
        }

        private void start() {
            long deadline = nanoTime.getAsLong() + timeout.toNanos();
            try {
                synchronized (this) {
                    timeoutTask = timeoutScheduler.schedule(
                            () -> fail("DEPLOYMENT_WAKE_UP_TIMEOUT"), timeout.toNanos(), TimeUnit.NANOSECONDS);
                    if (result.isDone()) timeoutTask.cancel(false);
                }
                submit(() -> readAndWake(deadline), this);
            } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
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
                        coordinator.setReplicas(generation, target, 1);
                    }
                });
                installLease(lease);
                schedulePoll(deadline);
            } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
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
            } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
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
                boolean runDeferred;
                long version;
                synchronized (this) {
                    if (retired) return;
                    schedulingPoll = true;
                    pollRanWhileScheduling = false;
                    version = ++pollVersion;
                    ScheduledFuture<?> scheduled;
                    try {
                        scheduled = timeoutScheduler.schedule(
                                () -> pollScheduled(version, deadline),
                                Math.min(pollInterval.toNanos(), remaining), TimeUnit.NANOSECONDS);
                    } finally {
                        schedulingPoll = false;
                    }
                    ScheduledFuture<?> previous = pollTask;
                    pollTask = scheduled;
                    if (previous != null) previous.cancel(false);
                    runDeferred = pollRanWhileScheduling;
                    if (runDeferred) pollTask = null; // NOSONAR (java:S2583): true only when the scheduler runs the poll inline and re-enters this monitor
                    if (retired) scheduled.cancel(false); // NOSONAR (java:S2583): true only when the scheduler runs the poll inline and re-enters this monitor
                }
                if (runDeferred) submit(() -> poll(deadline), this); // NOSONAR (java:S2583): true only when the scheduler runs the poll inline and re-enters this monitor
            } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
                completeExceptionally(failure);
            }
        }

        private void pollScheduled(long version, long deadline) {
            synchronized (this) {
                if (retired || version != pollVersion) return;
                if (schedulingPoll) {
                    pollRanWhileScheduling = true;
                    return;
                }
                pollTask = null;
            }
            submit(() -> poll(deadline), this);
        }

        private boolean canContinue() {
            if (result.isDone()) return false;
            if (closed.get()) {
                fail(WAKE_UP_CLOSED);
                return false;
            }
            if (!isActive(generation)) {
                fail(WAKE_UP_REMOVED);
                return false;
            }
            return true;
        }

        private synchronized void installLease(DeploymentWakeUpCoordinator.WakeUpLease lease) {
            if (retired) lease.close();
            else wakeUpLease = lease;
        }

        private void fail(String message) {
            result.completeExceptionally(new IllegalStateException(message));
        }

        private void completeExceptionally(Throwable failure) {
            result.completeExceptionally(failure);
        }

        private synchronized boolean callbackStarted() {
            if (retired) return false;
            runningCallbacks++;
            return true;
        }

        private synchronized void callbackFinished() {
            runningCallbacks--;
            drainIfPossible();
        }

        private synchronized void retire() {
            if (retired) return;
            retired = true;
            if (timeoutTask != null) timeoutTask.cancel(false);
            if (pollTask != null) pollTask.cancel(false);
            drainIfPossible();
        }

        private void drainIfPossible() {
            if (!retired || drained || runningCallbacks != 0) return;
            drained = true;
            inFlight.remove(generation, this);
            DeploymentWakeUpCoordinator.WakeUpLease lease = wakeUpLease;
            wakeUpLease = null;
            if (lease != null) lease.close();
        }
    }
}
