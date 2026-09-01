package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class InternalScaler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(InternalScaler.class);

    private final FunctionRegistry registry;
    private final ManagedDeploymentCoordinator deploymentCoordinator;
    private final ScalingProperties properties;
    private final ColdStartTracker coldStartTracker;
    private final ScalingDecisionCalculator decisionCalculator;
    private final ScalingCooldownTracker cooldownTracker;
    private final DeploymentWakeUpCoordinator wakeUpCoordinator;
    private final ScalingDecisionMetrics decisionMetrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public InternalScaler(FunctionRegistry registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedDeploymentCoordinator deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker) {
        this(registry, metricsReader, deploymentCoordinator, properties, coldStartTracker, new DeploymentWakeUpCoordinator());
    }

    public InternalScaler(FunctionRegistry registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedDeploymentCoordinator deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker,
                          DeploymentWakeUpCoordinator wakeUpCoordinator) {
        this(registry, metricsReader, deploymentCoordinator, properties, coldStartTracker,
                wakeUpCoordinator, null);
    }

    public InternalScaler(FunctionRegistry registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedDeploymentCoordinator deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker,
                          DeploymentWakeUpCoordinator wakeUpCoordinator,
                          ScalingDecisionMetrics decisionMetrics) {
        this.decisionMetrics = decisionMetrics;
        this.registry = registry;
        this.deploymentCoordinator = deploymentCoordinator;
        this.properties = properties;
        this.coldStartTracker = coldStartTracker;
        this.decisionCalculator = new ScalingDecisionCalculator(metricsReader);
        this.cooldownTracker = new ScalingCooldownTracker();
        this.wakeUpCoordinator = wakeUpCoordinator;
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
                registeredFunction.managedDeploymentTarget().ifPresent(target -> {
                    if (scaling != null
                        && scaling.strategy() == ScalingStrategy.INTERNAL) {
                        scaleFunction(target, spec);
                    }
                });
            }
        } catch (Exception ex) {
            log.error("Error in scaling loop", ex);
        }
    }

    private void scaleFunction(ManagedDeploymentTarget target, FunctionSpec spec) {
        try {
            evaluateAndScale(target, spec);
        } catch (Exception ex) {
            log.error("Error scaling function {}", spec.name(), ex);
        }
    }

    private void evaluateAndScale(ManagedDeploymentTarget target, FunctionSpec spec) {
        String functionName = spec.name();
        int currentReplicas = deploymentCoordinator.getReadyReplicas(target);
        ScalingDecision decision = decisionCalculator.calculate(spec, currentReplicas);
        if (decisionMetrics != null) {
            decisionMetrics.recordDecision(functionName, decision);
        }

        Instant now = Instant.now();
        if (decision.desiredReplicas() > decision.currentReplicas()) {
            if (!cooldownTracker.allowScaleUp(functionName, now)) {
                log.debug("Skipping scale-up for {} (cooldown)", functionName);
            } else {
                log.info("Scaling UP function {} from {} to {} replicas (maxRatio={})",
                        functionName, decision.currentReplicas(), decision.desiredReplicas(), decision.maxRatio());
                coldStartTracker.recordScaleUp(functionName, decision.currentReplicas(), decision.desiredReplicas());
                deploymentCoordinator.setReplicas(target, decision.desiredReplicas());
                cooldownTracker.recordScaleUp(functionName, now);
            }
        } else if (decision.downscaleSignal()) {
            if (!cooldownTracker.allowScaleDown(functionName, now)) {
                log.debug("Skipping scale-down for {} (cooldown)", functionName);
            } else {
                boolean scaled = wakeUpCoordinator.scaleDownIfUnprotected(target, () -> {
                    log.info("Scaling DOWN function {} from {} to {} replicas (maxRatio={})",
                            functionName, decision.currentReplicas(), decision.desiredReplicas(), decision.maxRatio());
                    deploymentCoordinator.setReplicas(target, decision.desiredReplicas());
                });
                if (scaled) {
                    cooldownTracker.recordScaleDown(functionName, now);
                } else {
                    log.debug("Skipping scale-down for {} while deployment wake-up is protected", functionName);
                }
            }
        }
    }

    void removeFunctionState(String functionName) {
        cooldownTracker.clear(functionName);
        coldStartTracker.removeFunctionState(functionName);
        wakeUpCoordinator.removeFunctionState(functionName);
    }
}
