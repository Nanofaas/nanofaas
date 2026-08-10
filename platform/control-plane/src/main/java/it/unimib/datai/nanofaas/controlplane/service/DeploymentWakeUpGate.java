package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class DeploymentWakeUpGate {

    private final FunctionRegistry registry;
    private final ManagedDeploymentCoordinator coordinator;
    private final Duration timeout;
    private final Duration pollInterval;
    private final Executor executor;
    private final DeploymentWakeUpCoordinator wakeUpCoordinator;
    private final ConcurrentMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public DeploymentWakeUpGate(FunctionRegistry registry,
                                 ManagedDeploymentCoordinator coordinator,
                                 DeploymentWakeUpProperties properties,
                                 @Qualifier("deploymentWakeUpExecutor") Executor executor,
                                 DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this(registry, coordinator, properties.timeout(), properties.pollInterval(), executor, wakeUpCoordinator);
    }

    public DeploymentWakeUpGate(FunctionRegistry registry,
                                 ManagedDeploymentCoordinator coordinator,
                                 Duration timeout,
                                 Duration pollInterval) {
        this(registry, coordinator, timeout, pollInterval, Runnable::run, new DeploymentWakeUpCoordinator());
    }

    DeploymentWakeUpGate(FunctionRegistry registry,
                          ManagedDeploymentCoordinator coordinator,
                          Duration timeout,
                          Duration pollInterval,
                          Executor executor) {
        this(registry, coordinator, timeout, pollInterval, executor, new DeploymentWakeUpCoordinator());
    }

    public DeploymentWakeUpGate(FunctionRegistry registry,
                                 ManagedDeploymentCoordinator coordinator,
                                 Duration timeout,
                                 Duration pollInterval,
                                 Executor executor,
                                 DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this.registry = registry;
        this.coordinator = coordinator;
        DeploymentWakeUpProperties properties = new DeploymentWakeUpProperties(timeout, pollInterval);
        this.timeout = properties.timeout();
        this.pollInterval = properties.pollInterval();
        this.executor = executor;
        this.wakeUpCoordinator = wakeUpCoordinator;
    }

    public CompletableFuture<Void> ensureReady(InvocationTask task) {
        Optional<RegisteredFunction> registered = registry.getRegistered(task.functionName());
        if (registered.isEmpty()) {
            return isEligible(task.functionSpec())
                    ? unavailableTarget()
                    : CompletableFuture.completedFuture(null);
        }
        RegisteredFunction function = registered.get();
        if (!isEligible(function)) {
            return CompletableFuture.completedFuture(null);
        }
        String backend = function.deploymentMetadata().deploymentBackend();
        if (backend == null || backend.isBlank()) {
            return unavailableTarget();
        }
        ManagedDeploymentTarget target = new ManagedDeploymentTarget(function.name(), backend);
        CompletableFuture<Void> wakeUp = new CompletableFuture<>();
        CompletableFuture<Void> existing = inFlight.putIfAbsent(function.name(), wakeUp);
        if (existing != null) {
            return existing;
        }
        wake(function.name(), target, wakeUp);
        return wakeUp;
    }

    private void wake(String name, ManagedDeploymentTarget target, CompletableFuture<Void> result) {
        long deadline = System.nanoTime() + timeout.toNanos();
        result.whenComplete((ignored, failure) -> inFlight.remove(name, result));
        CompletableFuture.delayedExecutor(timeout.toNanos(), TimeUnit.NANOSECONDS)
                .execute(() -> timeout(result));
        try {
            executor.execute(() -> start(target, deadline, result));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private void start(ManagedDeploymentTarget target, long deadline, CompletableFuture<Void> result) {
        try {
            ReplicaStatus status = coordinator.getReplicaStatus(target);
            if (result.isDone()) {
                return;
            }
            if (status.readyReplicas() > 0) {
                result.complete(null);
                return;
            }
            wakeUpCoordinator.protectAndScaleUp(target, deadline, () -> coordinator.setReplicas(target, 1));
            poll(target, deadline, result);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private void poll(ManagedDeploymentTarget target, long deadline, CompletableFuture<Void> result) {
        if (result.isDone()) {
            return;
        }
        try {
            ReplicaStatus status = coordinator.getReplicaStatus(target);
            if (result.isDone()) {
                return;
            }
            if (status.readyReplicas() > 0) {
                result.complete(null);
            } else {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    timeout(result);
                } else {
                    CompletableFuture.delayedExecutor(Math.min(pollInterval.toNanos(), remaining), TimeUnit.NANOSECONDS, executor)
                            .execute(() -> poll(target, deadline, result));
                }
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
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
        return CompletableFuture.failedFuture(new IllegalStateException("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE"));
    }

    private static void timeout(CompletableFuture<Void> result) {
        result.completeExceptionally(new IllegalStateException("DEPLOYMENT_WAKE_UP_TIMEOUT"));
    }
}
