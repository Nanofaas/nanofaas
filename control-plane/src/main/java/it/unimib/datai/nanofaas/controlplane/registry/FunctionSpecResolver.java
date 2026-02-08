package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;

import java.util.List;
import java.util.Map;

public class FunctionSpecResolver {
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
                scaling
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
                    List.of(new ScalingMetric("queue_depth", "5", null))
            );
        }
        return new ScalingConfig(
                config.strategy() == null ? ScalingStrategy.INTERNAL : config.strategy(),
                config.minReplicas() == null ? 1 : config.minReplicas(),
                config.maxReplicas() == null ? 10 : config.maxReplicas(),
                config.metrics() == null || config.metrics().isEmpty()
                        ? List.of(new ScalingMetric("queue_depth", "5", null))
                        : config.metrics()
        );
    }
}
