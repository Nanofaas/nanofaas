package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProtection;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.ApplicationListener;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class InternalScaler implements SmartLifecycle, ApplicationListener<DeploymentWakeUpProtection> {
    private static final Logger log = LoggerFactory.getLogger(InternalScaler.class);

    private final FunctionRegistry registry;
    private final ManagedDeploymentCoordinator deploymentCoordinator;
    private final ScalingProperties properties;
    private final ColdStartTracker coldStartTracker;
    private final ScalingDecisionCalculator decisionCalculator;
    private final ScalingCooldownTracker cooldownTracker;
    private final StaticPerPodConcurrencyController staticConcurrencyController;
    private final AdaptivePerPodConcurrencyController adaptiveConcurrencyController;
    private final ConcurrencyControlCoordinator concurrencyControlCoordinator;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentMap<String, Instant> wakeUpProtections = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    public InternalScaler(FunctionRegistry registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedDeploymentCoordinator deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker) {
        this.registry = registry;
        this.deploymentCoordinator = deploymentCoordinator;
        this.properties = properties;
        this.coldStartTracker = coldStartTracker;
        this.decisionCalculator = new ScalingDecisionCalculator(metricsReader);
        this.cooldownTracker = new ScalingCooldownTracker();
        this.staticConcurrencyController = new StaticPerPodConcurrencyController();
        this.adaptiveConcurrencyController = new AdaptivePerPodConcurrencyController();
        this.concurrencyControlCoordinator = new ConcurrencyControlCoordinator(
                metricsReader,
                properties,
                staticConcurrencyController,
                adaptiveConcurrencyController
        );
    }

    @Override
    public void start() {
        if (deploymentCoordinator == null) {
            log.info("InternalScaler disabled: no ManagedDeploymentCoordinator available");
            return;
        }
        if (running.compareAndSet(false, true)) {
            log.info("InternalScaler starting with poll interval {}ms", properties.pollIntervalMsOrDefault());
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nanofaas-internal-scaler");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(this::scalingLoop, properties.pollIntervalMsOrDefault(),
                    properties.pollIntervalMsOrDefault(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("InternalScaler stopping...");
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException _) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("InternalScaler stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // Package-private for testing
    void scalingLoop() {
        try {
            for (RegisteredFunction registeredFunction : registry.listRegistered()) {
                FunctionSpec spec = registeredFunction.spec();
                ScalingConfig scaling = spec.scalingConfig();
                managedDeploymentTarget(registeredFunction).ifPresent(target -> {
                    if (scaling != null
                        && scaling.strategy() == ScalingStrategy.INTERNAL) {
                        scaleFunction(target, spec, scaling);
                    }
                });
            }
        } catch (Exception ex) {
            log.error("Error in scaling loop", ex);
        }
    }

    private void scaleFunction(ManagedDeploymentTarget target, FunctionSpec spec, ScalingConfig scaling) {
        try {
            evaluateAndScale(target, spec, scaling);
        } catch (Exception ex) {
            log.error("Error scaling function {}", spec.name(), ex);
        }
    }

    private void evaluateAndScale(ManagedDeploymentTarget target, FunctionSpec spec, ScalingConfig scaling) {
        String functionName = spec.name();
        int currentReplicas = deploymentCoordinator.getReadyReplicas(target);
        ScalingDecision decision = decisionCalculator.calculate(spec, currentReplicas);

        Instant now = Instant.now();
        int effectiveReplicas = decision.effectiveReplicas();
        if (decision.desiredReplicas() > decision.currentReplicas()) {
            if (!cooldownTracker.allowScaleUp(functionName, now)) {
                log.debug("Skipping scale-up for {} (cooldown)", functionName);
            } else {
                log.info("Scaling UP function {} from {} to {} replicas (maxRatio={})",
                        functionName, decision.currentReplicas(), decision.desiredReplicas(), decision.maxRatio());
                coldStartTracker.recordScaleUp(functionName, decision.currentReplicas(), decision.desiredReplicas());
                deploymentCoordinator.setReplicas(target, decision.desiredReplicas());
                cooldownTracker.recordScaleUp(functionName, now);
                effectiveReplicas = decision.desiredReplicas();
            }
        } else if (decision.downscaleSignal()) {
            if (isWakeUpProtected(functionName, now)) {
                log.debug("Skipping scale-down for {} while deployment wake-up is protected", functionName);
            } else if (!cooldownTracker.allowScaleDown(functionName, now)) {
                log.debug("Skipping scale-down for {} (cooldown)", functionName);
            } else {
                log.info("Scaling DOWN function {} from {} to {} replicas (maxRatio={})",
                        functionName, decision.currentReplicas(), decision.desiredReplicas(), decision.maxRatio());
                deploymentCoordinator.setReplicas(target, decision.desiredReplicas());
                cooldownTracker.recordScaleDown(functionName, now);
                effectiveReplicas = decision.desiredReplicas();
            }
        }

        concurrencyControlCoordinator.apply(
                spec,
                scaling,
                decision.maxRatio(),
                effectiveReplicas,
                decision.downscaleSignal(),
                decision.currentReplicas()
        );
    }

    void removeFunctionState(String functionName) {
        cooldownTracker.clear(functionName);
        concurrencyControlCoordinator.removeFunctionState(functionName);
        coldStartTracker.removeFunctionState(functionName);
        wakeUpProtections.remove(functionName);
    }

    @Override
    public void onApplicationEvent(DeploymentWakeUpProtection protection) {
        wakeUpProtections.merge(protection.functionName(), protection.expiresAt(),
                (current, replacement) -> current.isAfter(replacement) ? current : replacement);
    }

    private boolean isWakeUpProtected(String functionName, Instant now) {
        Instant expiresAt = wakeUpProtections.get(functionName);
        if (expiresAt == null) {
            return false;
        }
        if (now.isBefore(expiresAt)) {
            return true;
        }
        wakeUpProtections.remove(functionName, expiresAt);
        return false;
    }

    private static Optional<ManagedDeploymentTarget> managedDeploymentTarget(RegisteredFunction function) {
        if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
            return Optional.empty();
        }
        String backendId = function.deploymentMetadata().deploymentBackend();
        return backendId == null || backendId.isBlank()
                ? Optional.empty()
                : Optional.of(new ManagedDeploymentTarget(function.name(), backendId));
    }
}
