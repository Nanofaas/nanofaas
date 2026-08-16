package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
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
    private static final int DEFAULT_TARGET_PER_POD = 2;
    private static final int DEFAULT_MIN_TARGET_PER_POD = 1;
    private static final int DEFAULT_MAX_TARGET_PER_POD = 8;
    private static final long DEFAULT_UPSCALE_COOLDOWN_MS = 30_000L;
    private static final long DEFAULT_DOWNSCALE_COOLDOWN_MS = 60_000L;
    // Fractions of latency degradation over the function's best observed service time:
    // back off above 2x, grow again below ~1.18x. See the concurrency-control module.
    private static final double DEFAULT_HIGH_LOAD_THRESHOLD = 0.5;
    private static final double DEFAULT_LOW_LOAD_THRESHOLD = 0.15;
    // A generic service-time SLO for a function that did not state one. Deliberately not
    // derived from anything the platform measures: a target the controller inferred from
    // observed latency would move whenever the function got slower, which is the one thing
    // an SLO must not do.
    private static final long DEFAULT_TARGET_LATENCY_MS = 250L;
    private static final double DEFAULT_WEIGHT = 1.0;
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
        if (config == null || config.mode() == null || config.mode() == ConcurrencyControlMode.FIXED) {
            return new ConcurrencyControlConfig(
                    ConcurrencyControlMode.FIXED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        if (config.mode() == ConcurrencyControlMode.BUDGETED) {
            return normalizeBudgeted(config);
        }
        if (config.mode() == ConcurrencyControlMode.SOJOURN) {
            return normalizeSojourn(config);
        }

        int min = Optional.ofNullable(config.minTargetInFlightPerPod())
                .map(v -> Math.max(1, v))
                .orElse(DEFAULT_MIN_TARGET_PER_POD);
        int max = Optional.ofNullable(config.maxTargetInFlightPerPod())
                .map(v -> Math.max(1, v))
                .orElse(DEFAULT_MAX_TARGET_PER_POD);
        if (min > max) {
            min = max;
        }

        int target = Optional.ofNullable(config.targetInFlightPerPod()).orElse(DEFAULT_TARGET_PER_POD);
        target = Math.clamp(target, min, max);

        return new ConcurrencyControlConfig(
                config.mode(),
                target,
                min,
                max,
                Optional.ofNullable(config.upscaleCooldownMs()).orElse(DEFAULT_UPSCALE_COOLDOWN_MS),
                Optional.ofNullable(config.downscaleCooldownMs()).orElse(DEFAULT_DOWNSCALE_COOLDOWN_MS),
                Optional.ofNullable(config.highLoadThreshold()).orElse(DEFAULT_HIGH_LOAD_THRESHOLD),
                Optional.ofNullable(config.lowLoadThreshold()).orElse(DEFAULT_LOW_LOAD_THRESHOLD)
        );
    }

    /**
     * SOJOURN searches for a minimum rather than stepping towards a per-replica target, so the
     * target and the gradient thresholds are left null. Its {@code targetLatencyMs} is an
     * end-to-end promise rather than a service-time one, and the weight is unused: the mode governs
     * one function at a time and has no budget to divide.
     */
    private ConcurrencyControlConfig normalizeSojourn(ConcurrencyControlConfig config) {
        long targetLatencyMs = Optional.ofNullable(config.targetLatencyMs())
                .filter(value -> value > 0)
                .orElse(DEFAULT_TARGET_LATENCY_MS);
        int min = Optional.ofNullable(config.minTargetInFlightPerPod())
                .map(value -> Math.max(1, value))
                .orElse(1);
        Integer max = config.maxTargetInFlightPerPod() == null
                ? null
                : Math.max(min, config.maxTargetInFlightPerPod());
        return new ConcurrencyControlConfig(
                ConcurrencyControlMode.SOJOURN,
                null,
                min,
                max,
                null,
                null,
                null,
                null,
                targetLatencyMs,
                null
        );
    }

    /**
     * BUDGETED states what the function needs, not how its controller steps, so the per-replica
     * target and the gradient thresholds are left null rather than filled with values that would
     * read as configuration nobody set.
     */
    private ConcurrencyControlConfig normalizeBudgeted(ConcurrencyControlConfig config) {
        long targetLatencyMs = Optional.ofNullable(config.targetLatencyMs())
                .filter(value -> value > 0)
                .orElse(DEFAULT_TARGET_LATENCY_MS);
        double weight = Optional.ofNullable(config.weight())
                .filter(value -> value > 0)
                .orElse(DEFAULT_WEIGHT);
        int min = Optional.ofNullable(config.minTargetInFlightPerPod())
                .map(value -> Math.max(1, value))
                .orElse(1);
        Integer max = config.maxTargetInFlightPerPod() == null
                ? null
                : Math.max(min, config.maxTargetInFlightPerPod());
        return new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED,
                null,
                min,
                max,
                null,
                null,
                null,
                null,
                targetLatencyMs,
                weight
        );
    }
}
