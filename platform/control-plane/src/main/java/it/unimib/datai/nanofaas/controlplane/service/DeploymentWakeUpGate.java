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
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Holds a dispatch to a managed deployment until it has a ready replica: a function scaled to zero, or
 * a deployment registered and not yet seen ready. The wake-up itself, shared by every caller of a
 * generation, is a {@link DeploymentWakeUp}; this class decides who waits and owns the wake-ups.
 */
public class DeploymentWakeUpGate
        implements DeploymentReadiness, FunctionRegistrationListener, AutoCloseable, MeterBinder {
    private static final String WAKE_UP_CLOSED = "DEPLOYMENT_WAKE_UP_CLOSED";
    private static final String WAKE_UP_REMOVED = "DEPLOYMENT_WAKE_UP_REMOVED";

    private final FunctionRegistry registry;
    private final ManagedDeploymentCoordinator coordinator;
    private final FunctionCapacityRegistry generations;
    private final Duration timeout;
    private final Duration readyObservationMaxAge;
    private final DeploymentWakeUpCoordinator wakeUpCoordinator;
    private final InstantSource clock;
    private final LongSupplier nanoTime;
    private final DeploymentWakeUp.Context wakeUpContext;
    private final ConcurrentMap<FunctionGeneration, DeploymentWakeUp> inFlight = new ConcurrentHashMap<>();
    /**
     * Registered DEPLOYMENT generations not yet observed ready, by function name. A fresh
     * Deployment is not Ready for the seconds its first pod takes to start, and a dispatch sent
     * then meets a Service with no endpoints; holding it here waits once instead of spending
     * retries. Keyed by name so the ordinary path, a function already seen ready, costs one
     * lookup and no provider call.
     */
    private final ConcurrentMap<String, FunctionGeneration> awaitingFirstReadiness = new ConcurrentHashMap<>();
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
        this.readyObservationMaxAge = properties.readyObservationMaxAge();
        this.wakeUpCoordinator = Objects.requireNonNull(wakeUpCoordinator, "wakeUpCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.wakeUpContext = new DeploymentWakeUp.Context(coordinator, wakeUpCoordinator,
                Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(timeoutScheduler, "timeoutScheduler"),
                nanoTime, properties.pollInterval(), this::stopReason,
                (generation, wakeUp) -> inFlight.remove(generation, wakeUp));
    }

    /** {@code deployment_wakeups_in_flight}: the wake-ups under way, one per waking function generation. */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("deployment_wakeups_in_flight", inFlight, ConcurrentMap::size)
                .description("Deployment wake-ups under way, one per waking function generation")
                .register(registry);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored") // The caller owns the outcome; the callback only lifts the first-readiness hold.
    public CompletableFuture<Void> ensureReady(InvocationTask task) {
        if (closed.get()) {
            return failed(WAKE_UP_CLOSED);
        }
        Optional<RegisteredFunction> registered = registry.getRegistered(task.functionName());
        if (registered.isEmpty()) {
            return isEligible(task.functionSpec()) ? unavailableTarget() : CompletableFuture.completedFuture(null);
        }
        RegisteredFunction function = registered.get();
        boolean scaleToZero = isEligible(function);
        FunctionGeneration awaitingReady = awaitingFirstReadiness.get(function.name());
        if (!scaleToZero && (awaitingReady == null || !isDeployment(function))) {
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
        if (!scaleToZero && !generation.equals(awaitingReady)) {
            return CompletableFuture.completedFuture(null);
        }
        ManagedDeploymentTarget target = new ManagedDeploymentTarget(function.name(), backend);
        ReplicaObservation observation = coordinator.observeReplicaStatus(target);
        CompletableFuture<Void> outcome = isReadyWithinPolicy(observation, clock.instant())
                ? readyOutcome(generation)
                : joinOrStartWakeUp(generation, target, saysReady(observation));
        if (awaitingReady != null) {
            // Once ready, this generation takes the ordinary path: no wait unless it scales to zero.
            outcome.thenRun(() -> awaitingFirstReadiness.remove(function.name(), generation));
        }
        return outcome;
    }

    private CompletableFuture<Void> readyOutcome(FunctionGeneration generation) {
        if (closed.get()) {
            return failed(WAKE_UP_CLOSED);
        }
        if (!isActive(generation)) {
            return failed(WAKE_UP_REMOVED);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Every caller waits its own budget: one that joins a wake-up under way extends it, and the
     * scale-down protection with it, to its own deadline instead of inheriting what is left.
     */
    private CompletableFuture<Void> joinOrStartWakeUp(FunctionGeneration generation, ManagedDeploymentTarget target,
                                                      boolean lastReadingWasReady) {
        if (closed.get() || !isActive(generation)) {
            return closedOrRemoved();
        }
        long callerDeadline = nanoTime.getAsLong() + timeout.toNanos();
        DeploymentWakeUp owner;
        boolean startOwner = false;
        synchronized (ownerLifecycle) {
            if (closed.get() || !isActive(generation)) {
                return closedOrRemoved();
            }
            DeploymentWakeUp candidate = new DeploymentWakeUp(generation, target, lastReadingWasReady, wakeUpContext);
            owner = inFlight.putIfAbsent(generation, candidate);
            if (owner == null) {
                owner = candidate;
                startOwner = true;
            }
        }
        if (startOwner) {
            owner.start(callerDeadline);
        } else {
            owner.extendTo(callerDeadline);
        }
        return owner.callerView(callerDeadline);
    }

    private CompletableFuture<Void> closedOrRemoved() {
        return failed(closed.get() ? WAKE_UP_CLOSED : WAKE_UP_REMOVED);
    }

    @Override
    public void onRegister(FunctionSpec spec) {
        FunctionGeneration generation = generations.activeGeneration(spec.name());
        if (generation != null) {
            wakeUpCoordinator.restoreFunctionState(generation);
            if (spec.executionMode() == ExecutionMode.DEPLOYMENT) {
                awaitingFirstReadiness.put(spec.name(), generation);
            }
        }
    }

    @Override
    public void onRemove(String functionName) {
        synchronized (ownerLifecycle) {
            for (DeploymentWakeUp wakeUp : new ArrayList<>(inFlight.values())) {
                if (wakeUp.generation().functionName().equals(functionName)) {
                    wakeUp.fail(WAKE_UP_REMOVED);
                }
            }
        }
        wakeUpCoordinator.removeFunctionState(functionName);
        awaitingFirstReadiness.remove(functionName);
    }

    @Override
    public void close() {
        synchronized (ownerLifecycle) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (DeploymentWakeUp wakeUp : new ArrayList<>(inFlight.values())) {
                wakeUp.fail(WAKE_UP_CLOSED);
            }
        }
    }

    /** Why a wake-up of {@code generation} must stop, or null while it may go on. */
    private String stopReason(FunctionGeneration generation) {
        if (closed.get()) return WAKE_UP_CLOSED;
        return isActive(generation) ? null : WAKE_UP_REMOVED;
    }

    private FunctionGeneration activeGeneration(RegisteredFunction function) {
        return coordinator.generationOf(function);
    }

    private boolean isReadyWithinPolicy(ReplicaObservation observation, Instant now) {
        return observation instanceof ReplicaObservation.Available(var status, var state, var observedAt)
                && state == ReplicaObservation.State.FRESH
                && status.desiredReplicas() > 0
                && status.readyReplicas() > 0
                && !observedAt.isBefore(now.minus(readyObservationMaxAge));
    }

    /** Whether the reading, however old, saw the function ready: what to fall back on if no fresh one comes. */
    private static boolean saysReady(ReplicaObservation observation) {
        return observation instanceof ReplicaObservation.Available available
                && available.status().desiredReplicas() > 0
                && available.status().readyReplicas() > 0;
    }

    private boolean isCurrent(FunctionGeneration generation) {
        return generation.equals(generations.activeGeneration(generation.functionName()));
    }

    private boolean isActive(FunctionGeneration generation) {
        return isCurrent(generation) && registry.getRegistered(generation.functionName()).isPresent();
    }

    private static boolean isEligible(RegisteredFunction function) {
        return isDeployment(function) && isEligible(function.spec());
    }

    private static boolean isDeployment(RegisteredFunction function) {
        DeploymentMetadata metadata = function.deploymentMetadata();
        return metadata.effectiveExecutionMode() == ExecutionMode.DEPLOYMENT;
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
}
