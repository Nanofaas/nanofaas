package it.unimib.datai.mcfaas.controlplane.registry;

import it.unimib.datai.mcfaas.common.model.ExecutionMode;
import it.unimib.datai.mcfaas.common.model.FunctionSpec;
import it.unimib.datai.mcfaas.common.model.RuntimeMode;

import java.util.List;
import java.util.Map;

public class FunctionSpecResolver {
    private final FunctionDefaults defaults;

    public FunctionSpecResolver(FunctionDefaults defaults) {
        this.defaults = defaults;
    }

    public FunctionSpec resolve(FunctionSpec spec) {
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
                spec.executionMode() == null ? ExecutionMode.REMOTE : spec.executionMode(),
                spec.runtimeMode() == null ? RuntimeMode.HTTP : spec.runtimeMode(),
                spec.runtimeCommand()
        );
    }
}
