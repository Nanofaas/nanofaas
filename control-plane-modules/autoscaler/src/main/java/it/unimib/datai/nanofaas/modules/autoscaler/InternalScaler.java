package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
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
    private final ScalingMetricsReader metricsReader;
    private final KubernetesResourceManager resourceManager;
    private final ScalingProperties properties;
    private final ColdStartTracker coldStartTracker;
    private final ScalingDecisionCalculator decisionCalculator;
    private final ScalingCooldownTracker cooldownTracker;
    private final StaticPerPodConcurrencyController staticConcurrencyController;
    private final AdaptivePerPodConcurrencyController adaptiveConcurrencyController;
    private final ConcurrencyControlCoordinator concurrencyControlCoordinator;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public InternalScaler(FunctionRegistry registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) KubernetesResourceManager resourceManager,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker) {
        this.registry = registry;
        this.metricsReader = metricsReader;
        this.resourceManager = resourceManager;
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
        if (resourceManager == null) {
            log.info("InternalScaler disabled: no KubernetesResourceManager available");
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
                } catch (InterruptedException ex) {
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
            for (FunctionSpec spec : registry.list()) {
                if (spec.executionMode() != ExecutionMode.DEPLOYMENT) {
                    continue;
                }
                ScalingConfig scaling = spec.scalingConfig();
                if (scaling == null || scaling.strategy() != ScalingStrategy.INTERNAL) {
                    continue;
                }
                try {
                    evaluateAndScale(spec, scaling);
                } catch (Exception ex) {
                    log.error("Error scaling function {}", spec.name(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("Error in scaling loop", ex);
        }
    }

    private void evaluateAndScale(FunctionSpec spec, ScalingConfig scaling) {
        String functionName = spec.name();
        int currentReplicas = resourceManager.getReadyReplicas(functionName);
        ScalingDecision decision = decisionCalculator.calculate(spec, currentReplicas);

        Instant now = Instant.now();
        boolean scaled = false;
        int effectiveReplicas = decision.effectiveReplicas();
        if (decision.desiredReplicas() > decision.currentReplicas()) {
            if (!cooldownTracker.allowScaleUp(functionName, now)) {
                log.debug("Skipping scale-up for {} (cooldown)", functionName);
            } else {
                log.info("Scaling UP function {} from {} to {} replicas (maxRatio={})",
                        functionName, decision.currentReplicas(), decision.desiredReplicas(), decision.maxRatio());
                coldStartTracker.recordScaleUp(functionName, decision.currentReplicas(), decision.desiredReplicas());
                resourceManager.setReplicas(functionName, decision.desiredReplicas());
                cooldownTracker.recordScaleUp(functionName, now);
                scaled = true;
                effectiveReplicas = decision.desiredReplicas();
            }
        } else if (decision.downscaleSignal()) {
            if (!cooldownTracker.allowScaleDown(functionName, now)) {
                log.debug("Skipping scale-down for {} (cooldown)", functionName);
            } else {
                log.info("Scaling DOWN function {} from {} to {} replicas (maxRatio={})",
                        functionName, decision.currentReplicas(), decision.desiredReplicas(), decision.maxRatio());
                resourceManager.setReplicas(functionName, decision.desiredReplicas());
                cooldownTracker.recordScaleDown(functionName, now);
                scaled = true;
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
    }
}
