package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;

import java.util.List;
import java.util.Map;

public class FunctionSpecResolver {
    private static final int DEFAULT_TARGET_PER_POD = 2;
    private static final int DEFAULT_MIN_TARGET_PER_POD = 1;
    private static final int DEFAULT_MAX_TARGET_PER_POD = 8;
    private static final long DEFAULT_UPSCALE_COOLDOWN_MS = 30_000L;
    private static final long DEFAULT_DOWNSCALE_COOLDOWN_MS = 60_000L;
    private static final double DEFAULT_HIGH_LOAD_THRESHOLD = 0.85;
    private static final double DEFAULT_LOW_LOAD_THRESHOLD = 0.35;

    private final FunctionDefaults defaults;

    public FunctionSpecResolver(FunctionDefaults defaults) {
        this.defaults = defaults;
    }

    public FunctionSpec resolve(FunctionSpec spec) {
        ExecutionMode mode = spec.executionMode() == null ? ExecutionMode.DEPLOYMENT : spec.executionMode();
        ScalingConfig scaling = resolveScalingConfig(spec.scalingConfig(), mode);
        return new FunctionSpec(
                spec.name(),
                spec.image(),
                spec.command() == null ? List.of() : spec.command(),
                spec.env() == null ? Map.of() : spec.env(),
                spec.resources(),
                spec.timeoutMs() == null ? defaults.timeoutMs() : spec.timeoutMs(),
                spec.concurrency() == null ? defaults.concurrency() : spec.concurrency(),
                spec.queueSize() == null ? defaults.queueSize() : spec.queueSize(),
                spec.maxRetries() == null ? defaults.maxRetries() : spec.maxRetries(),
                spec.endpointUrl(),
                mode,
                spec.runtimeMode() == null ? RuntimeMode.HTTP : spec.runtimeMode(),
                spec.runtimeCommand(),
                scaling,
                spec.imagePullSecrets()
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
                    List.of(new ScalingMetric("queue_depth", "5", null)),
                    normalizeConcurrencyControl(null)
            );
        }
        return new ScalingConfig(
                config.strategy() == null ? ScalingStrategy.INTERNAL : config.strategy(),
                config.minReplicas() == null ? 1 : config.minReplicas(),
                config.maxReplicas() == null ? 10 : config.maxReplicas(),
                config.metrics() == null || config.metrics().isEmpty()
                        ? List.of(new ScalingMetric("queue_depth", "5", null))
                        : config.metrics(),
                normalizeConcurrencyControl(config.concurrencyControl())
        );
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

        int min = config.minTargetInFlightPerPod() == null
                ? DEFAULT_MIN_TARGET_PER_POD
                : Math.max(1, config.minTargetInFlightPerPod());
        int max = config.maxTargetInFlightPerPod() == null
                ? DEFAULT_MAX_TARGET_PER_POD
                : Math.max(1, config.maxTargetInFlightPerPod());
        if (min > max) {
            min = max;
        }

        int target = config.targetInFlightPerPod() == null
                ? DEFAULT_TARGET_PER_POD
                : config.targetInFlightPerPod();
        target = Math.max(min, Math.min(max, target));

        return new ConcurrencyControlConfig(
                config.mode(),
                target,
                min,
                max,
                config.upscaleCooldownMs() == null ? DEFAULT_UPSCALE_COOLDOWN_MS : config.upscaleCooldownMs(),
                config.downscaleCooldownMs() == null ? DEFAULT_DOWNSCALE_COOLDOWN_MS : config.downscaleCooldownMs(),
                config.highLoadThreshold() == null ? DEFAULT_HIGH_LOAD_THRESHOLD : config.highLoadThreshold(),
                config.lowLoadThreshold() == null ? DEFAULT_LOW_LOAD_THRESHOLD : config.lowLoadThreshold()
        );
    }
}
