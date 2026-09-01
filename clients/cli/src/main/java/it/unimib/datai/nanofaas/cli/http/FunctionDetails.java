package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
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
                && matchesIfSpecified(runtimeMode, requested.runtimeMode())
                && matchesIfSpecified(runtimeCommand, requested.runtimeCommand())
                && matchesIfSpecified(scalingConfig, requested.scalingConfig())
                && matchesIfSpecified(imagePullSecrets, requested.imagePullSecrets())
                && matchesIfSpecified(offload, requested.offload());
    }

    private static boolean matchesIfSpecified(Object actual, Object requested) {
        return requested == null || Objects.equals(actual, requested);
    }

    public FunctionPatch mutablePatch(FunctionSpec requested) {
        Integer concurrency = matchesIfSpecified(this.concurrency, requested.concurrency())
                ? null : requested.concurrency();
        Integer timeoutMs = matchesIfSpecified(this.timeoutMs, requested.timeoutMs())
                ? null : requested.timeoutMs();
        Integer maxRetries = matchesIfSpecified(this.maxRetries, requested.maxRetries())
                ? null : requested.maxRetries();

        ConcurrencyControlConfig requestedCc = requested.scalingConfig() == null
                ? null : requested.scalingConfig().concurrencyControl();
        ConcurrencyControlConfig currentCc = scalingConfig == null
                ? null : scalingConfig.concurrencyControl();
        boolean deployment = (requested.executionMode() == null ? ExecutionMode.DEPLOYMENT : requested.executionMode()) == ExecutionMode.DEPLOYMENT;
        ConcurrencyControlConfig expected = requestedCc == null ? null
                : deployment ? ConcurrencyControlConfig.normalize(requestedCc) : requestedCc;
        ConcurrencyControlConfig concurrencyControl =
                requestedCc != null && !Objects.equals(currentCc, expected) ? requestedCc : null;

        return new FunctionPatch(concurrency, timeoutMs, maxRetries, concurrencyControl);
    }

    public boolean hasImmutableDifferences(FunctionSpec requested) {
        ExecutionMode requestedMode = requested.executionMode() == null
                ? ExecutionMode.DEPLOYMENT
                : requested.executionMode();
        boolean endpointMatches = requestedMode == ExecutionMode.DEPLOYMENT
                || matchesIfSpecified(endpointUrl, requested.endpointUrl());

        ScalingConfig requestedScaling = requested.scalingConfig();
        boolean scalingImmutableDiffers = requestedScaling != null && (
                !matchesIfSpecified(scalingConfig == null ? null : scalingConfig.strategy(), requestedScaling.strategy())
                        || !matchesIfSpecified(scalingConfig == null ? null : scalingConfig.minReplicas(), requestedScaling.minReplicas())
                        || !matchesIfSpecified(scalingConfig == null ? null : scalingConfig.maxReplicas(), requestedScaling.maxReplicas())
                        || !matchesIfSpecified(scalingConfig == null ? null : scalingConfig.metrics(), requestedScaling.metrics()));

        return !matchesIfSpecified(image, requested.image())
                || !matchesIfSpecified(command, requested.command())
                || !matchesIfSpecified(env, requested.env())
                || !matchesIfSpecified(resources, requested.resources())
                || !matchesIfSpecified(queueSize, requested.queueSize())
                || !endpointMatches
                || requestedExecutionMode != requestedMode
                || !matchesIfSpecified(runtimeMode, requested.runtimeMode())
                || !matchesIfSpecified(runtimeCommand, requested.runtimeCommand())
                || scalingImmutableDiffers
                || !matchesIfSpecified(imagePullSecrets, requested.imagePullSecrets())
                || !matchesIfSpecified(offload, requested.offload());
    }

    public FunctionSpec toSpec() {
        return new FunctionSpec(name, image, command, env, resources, timeoutMs, concurrency, queueSize,
                maxRetries, endpointUrl, requestedExecutionMode, runtimeMode, runtimeCommand,
                scalingConfig, imagePullSecrets, offload);
    }
}
