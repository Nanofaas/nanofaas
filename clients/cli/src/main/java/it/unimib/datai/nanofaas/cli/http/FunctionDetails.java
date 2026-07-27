package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionDetails(
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
         * Names of the objects the deployment backend created — the Deployment,
         * Service and namespace on Kubernetes. Carried so `fn get` reprints what
         * the control plane reported instead of dropping it on the way through.
         *
         * <p>Not part of {@link #matches}: it is derived by the backend, never
         * requested, so it says nothing about whether a function has drifted.
         */
        Map<String, String> deploymentObjects
) {
    public boolean matches(FunctionSpec requested) {
        ExecutionMode requestedMode = requested.executionMode() == null
                ? ExecutionMode.DEPLOYMENT
                : requested.executionMode();
        RuntimeMode requestedRuntime = requested.runtimeMode() == null
                ? RuntimeMode.HTTP
                : requested.runtimeMode();
        boolean endpointMatches = requestedMode == ExecutionMode.DEPLOYMENT
                || matchesIfSpecified(endpointUrl, requested.endpointUrl());
        return Objects.equals(name, requested.name())
                && Objects.equals(image, requested.image())
                && matchesIfSpecified(command, requested.command())
                && matchesIfSpecified(env, requested.env())
                && matchesIfSpecified(resources, requested.resources())
                && matchesIfSpecified(timeoutMs, requested.timeoutMs())
                && matchesIfSpecified(concurrency, requested.concurrency())
                && matchesIfSpecified(queueSize, requested.queueSize())
                && matchesIfSpecified(maxRetries, requested.maxRetries())
                && endpointMatches
                && requestedExecutionMode == requestedMode
                && runtimeMode == requestedRuntime
                && matchesIfSpecified(runtimeCommand, requested.runtimeCommand())
                && matchesIfSpecified(scalingConfig, requested.scalingConfig())
                && matchesIfSpecified(imagePullSecrets, requested.imagePullSecrets())
                && matchesIfSpecified(offload, requested.offload());
    }

    private static boolean matchesIfSpecified(Object actual, Object requested) {
        return requested == null || Objects.equals(actual, requested);
    }
}
