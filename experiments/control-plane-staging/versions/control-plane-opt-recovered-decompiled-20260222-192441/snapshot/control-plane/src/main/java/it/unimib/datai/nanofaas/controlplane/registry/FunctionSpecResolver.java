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
import java.util.Optional;

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
                Optional.ofNullable(spec.runtimeMode()).orElse(RuntimeMode.HTTP),
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
                Optional.ofNullable(config.strategy()).orElse(ScalingStrategy.INTERNAL),
                Optional.ofNullable(config.minReplicas()).orElse(1),
                Optional.ofNullable(config.maxReplicas()).orElse(10),
                Optional.ofNullable(config.metrics()).filter(m -> !m.isEmpty())
                        .orElseGet(() -> List.of(new ScalingMetric("queue_depth", "5", null))),
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
        target = Math.max(min, Math.min(max, target));

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
}
