package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class FunctionSpecResolver {
    private static final String QUEUE_DEPTH_METRIC = "queue_depth";

    private final FunctionDefaults defaults;
    private static final Set<String> SUPPORTED_INTERNAL_SCALING_METRICS = Set.of(QUEUE_DEPTH_METRIC, "in_flight", "rps");

    public FunctionSpecResolver(FunctionDefaults defaults) {
        this.defaults = defaults;
    }

    public FunctionSpec resolve(FunctionSpec spec) {
        ExecutionMode mode = Optional.ofNullable(spec.executionMode()).orElse(ExecutionMode.DEPLOYMENT);
        ScalingConfig scaling = resolveScalingConfig(spec.scalingConfig(), mode);
        return new FunctionSpec(
                spec.name(),
                spec.image(),
                Optional.ofNullable(spec.command()).orElse(List.of()),
                Optional.ofNullable(spec.env()).orElse(Map.of()),
                spec.resources(),
                Optional.ofNullable(spec.timeoutMs()).orElse(defaults.timeoutMs()),
                Optional.ofNullable(spec.concurrency()).orElse(defaults.concurrency()),
                Optional.ofNullable(spec.queueSize()).orElse(defaults.queueSize()),
                Optional.ofNullable(spec.maxRetries()).orElse(defaults.maxRetries()),
                spec.endpointUrl(),
                mode,
                spec.runtimeMode(),
                spec.runtimeCommand(),
                scaling,
                spec.imagePullSecrets(),
                spec.offload()
        );
    }

    private ScalingConfig resolveScalingConfig(ScalingConfig config, ExecutionMode mode) {
        if (mode != ExecutionMode.DEPLOYMENT) {
            return config;
        }
        if (config == null) {
            return new ScalingConfig(
                    ScalingStrategy.INTERNAL,
                    1,
                    10,
                    List.of(new ScalingMetric(QUEUE_DEPTH_METRIC, "5", null)),
                    normalizeConcurrencyControl(null)
            );
        }
        ScalingStrategy strategy = Optional.ofNullable(config.strategy()).orElse(ScalingStrategy.INTERNAL);
        List<ScalingMetric> metrics = Optional.ofNullable(config.metrics()).filter(m -> !m.isEmpty())
                .orElseGet(() -> List.of(new ScalingMetric(QUEUE_DEPTH_METRIC, "5", null)));
        if (strategy == ScalingStrategy.INTERNAL) {
            validateInternalScalingMetrics(metrics);
        }
        int minReplicas = Optional.ofNullable(config.minReplicas()).orElse(1);
        int maxReplicas = Optional.ofNullable(config.maxReplicas()).orElse(10);
        if (minReplicas < 0) {
            throw new IllegalArgumentException("minReplicas must be >= 0");
        }
        if (maxReplicas < 1) {
            throw new IllegalArgumentException("maxReplicas must be >= 1");
        }
        if (minReplicas > maxReplicas) {
            throw new IllegalArgumentException("minReplicas must be <= maxReplicas");
        }
        return new ScalingConfig(
                strategy,
                minReplicas,
                maxReplicas,
                metrics,
                normalizeConcurrencyControl(config.concurrencyControl())
        );
    }

    private void validateInternalScalingMetrics(List<ScalingMetric> metrics) {
        for (ScalingMetric metric : metrics) {
            if (metric == null || metric.type() == null || !SUPPORTED_INTERNAL_SCALING_METRICS.contains(metric.type())) {
                throw new IllegalArgumentException("Unsupported INTERNAL scaling metric: " + (metric == null ? null : metric.type()));
            }
        }
    }

    private ConcurrencyControlConfig normalizeConcurrencyControl(ConcurrencyControlConfig config) {
        return ConcurrencyControlConfig.normalize(config);
    }
}
