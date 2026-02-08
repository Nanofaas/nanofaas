package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InternalScaler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(InternalScaler.class);

    private final FunctionRegistry registry;
    private final ScalingMetricsReader metricsReader;
    private final KubernetesResourceManager resourceManager;
    private final ScalingProperties properties;
    private final Map<String, Instant> lastScaleUp = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastScaleDown = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public InternalScaler(FunctionRegistry registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) KubernetesResourceManager resourceManager,
                          ScalingProperties properties) {
        this.registry = registry;
        this.metricsReader = metricsReader;
        this.resourceManager = resourceManager;
        this.properties = properties;
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
                evaluateAndScale(spec, scaling);
            }
        } catch (Exception ex) {
            log.error("Error in scaling loop", ex);
        }
    }

    private void evaluateAndScale(FunctionSpec spec, ScalingConfig scaling) {
        String functionName = spec.name();
        int currentReplicas = resourceManager.getReadyReplicas(functionName);
        if (currentReplicas <= 0) {
            currentReplicas = scaling.minReplicas();
        }

        double maxRatio = 0.0;
        if (scaling.metrics() != null) {
            for (ScalingMetric metric : scaling.metrics()) {
                double currentValue = metricsReader.readMetric(functionName, metric);
                double targetValue = parseTarget(metric.target());
                if (targetValue > 0) {
                    double ratio = currentValue / targetValue;
                    maxRatio = Math.max(maxRatio, ratio);
                }
            }
        }

        int desiredReplicas = (int) Math.ceil(maxRatio * currentReplicas);
        desiredReplicas = Math.max(scaling.minReplicas(), Math.min(scaling.maxReplicas(), desiredReplicas));

        if (desiredReplicas == currentReplicas) {
            return;
        }

        Instant now = Instant.now();
        if (desiredReplicas > currentReplicas) {
            // Scale up
            Instant lastUp = lastScaleUp.get(functionName);
            long cooldownMs = scaling.metrics() != null && !scaling.metrics().isEmpty() ? 30_000 : 30_000;
            if (lastUp != null && now.toEpochMilli() - lastUp.toEpochMilli() < cooldownMs) {
                log.debug("Skipping scale-up for {} (cooldown)", functionName);
                return;
            }
            log.info("Scaling UP function {} from {} to {} replicas (maxRatio={})",
                    functionName, currentReplicas, desiredReplicas, maxRatio);
            resourceManager.setReplicas(functionName, desiredReplicas);
            lastScaleUp.put(functionName, now);
        } else {
            // Scale down
            Instant lastDown = lastScaleDown.get(functionName);
            long cooldownMs = 60_000;
            if (lastDown != null && now.toEpochMilli() - lastDown.toEpochMilli() < cooldownMs) {
                log.debug("Skipping scale-down for {} (cooldown)", functionName);
                return;
            }
            log.info("Scaling DOWN function {} from {} to {} replicas (maxRatio={})",
                    functionName, currentReplicas, desiredReplicas, maxRatio);
            resourceManager.setReplicas(functionName, desiredReplicas);
            lastScaleDown.put(functionName, now);
        }
    }

    private double parseTarget(String target) {
        try {
            return Double.parseDouble(target);
        } catch (NumberFormatException e) {
            return 50.0;
        }
    }
}
