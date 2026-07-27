package it.unimib.datai.nanofaas.controlplane.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionResponse(
        String name,
        String image,
        List<String> command,
        Map<String, String> env,
        ResourceSpec resources,
        Integer timeoutMs,
        Integer concurrency,
        Integer queueSize,
        Integer maxRetries,
        String endpointUrl,
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        RuntimeMode runtimeMode,
        String runtimeCommand,
        ScalingConfig scalingConfig,
        List<String> imagePullSecrets,
        OffloadPolicy offload,
        /**
         * Names of the objects the deployment backend created for this function —
         * on Kubernetes the Deployment, Service and namespace. Absent for
         * non-managed functions, which have no such objects.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> deploymentObjects
) {
    public static FunctionResponse from(FunctionSpec spec,
                                        ExecutionMode requestedExecutionMode,
                                        ExecutionMode effectiveExecutionMode,
                                        String deploymentBackend,
                                        String degradationReason,
                                        String endpointUrl) {
        return from(spec, requestedExecutionMode, effectiveExecutionMode, deploymentBackend,
                degradationReason, endpointUrl, Map.of());
    }

    public static FunctionResponse from(FunctionSpec spec,
                                        ExecutionMode requestedExecutionMode,
                                        ExecutionMode effectiveExecutionMode,
                                        String deploymentBackend,
                                        String degradationReason,
                                        String endpointUrl,
                                        Map<String, String> deploymentObjects) {
        return new FunctionResponse(
                spec.name(),
                spec.image(),
                spec.command(),
                spec.env(),
                spec.resources(),
                spec.timeoutMs(),
                spec.concurrency(),
                spec.queueSize(),
                spec.maxRetries(),
                endpointUrl != null ? endpointUrl : spec.endpointUrl(),
                requestedExecutionMode,
                effectiveExecutionMode,
                deploymentBackend,
                degradationReason,
                spec.runtimeMode(),
                spec.runtimeCommand(),
                spec.scalingConfig(),
                spec.imagePullSecrets(),
                spec.offload(),
                deploymentObjects
        );
    }

    public static FunctionResponse fromNonManaged(FunctionSpec spec) {
        ExecutionMode mode = spec.executionMode();
        return from(spec, mode, mode, null, null, spec.endpointUrl());
    }

    public static FunctionResponse from(RegisteredFunction registeredFunction) {
        return from(
                registeredFunction.spec(),
                registeredFunction.deploymentMetadata().requestedExecutionMode(),
                registeredFunction.deploymentMetadata().effectiveExecutionMode(),
                registeredFunction.deploymentMetadata().deploymentBackend(),
                registeredFunction.deploymentMetadata().degradationReason(),
                registeredFunction.deploymentMetadata().effectiveEndpointUrl(),
                registeredFunction.deploymentMetadata().deploymentObjects()
        );
    }
}
