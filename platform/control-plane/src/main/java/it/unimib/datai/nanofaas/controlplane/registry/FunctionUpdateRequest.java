package it.unimib.datai.nanofaas.controlplane.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import jakarta.validation.constraints.Min;

/**
 * Partial update of a registered function. Only fields that the control plane can apply without
 * touching the deployment are accepted; a null field leaves the current value alone.
 *
 * <p>Unknown properties are rejected rather than ignored, so patching an immutable field such as
 * {@code image} or {@code executionMode} fails loudly instead of appearing to succeed.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionUpdateRequest(
        @Min(1) Integer concurrency,
        @Min(1) Integer timeoutMs,
        @Min(0) Integer maxRetries,
        ConcurrencyControlConfig concurrencyControl
) {

    /**
     * Returns a copy of {@code current} with the non-null fields of this request applied.
     * {@code concurrencyControl} replaces the block wholesale — it is not merged field by field.
     */
    public FunctionSpec applyTo(FunctionSpec current) {
        return new FunctionSpec(
                current.name(),
                current.image(),
                current.command(),
                current.env(),
                current.resources(),
                timeoutMs == null ? current.timeoutMs() : timeoutMs,
                concurrency == null ? current.concurrency() : concurrency,
                current.queueSize(),
                maxRetries == null ? current.maxRetries() : maxRetries,
                current.endpointUrl(),
                current.executionMode(),
                current.runtimeMode(),
                current.runtimeCommand(),
                concurrencyControl == null
                        ? current.scalingConfig()
                        : withConcurrencyControl(current.scalingConfig(), concurrencyControl),
                current.imagePullSecrets(),
                current.offload()
        );
    }

    private static ScalingConfig withConcurrencyControl(ScalingConfig current, ConcurrencyControlConfig control) {
        return current == null
                ? new ScalingConfig(null, null, null, null, control)
                : new ScalingConfig(
                        current.strategy(),
                        current.minReplicas(),
                        current.maxReplicas(),
                        current.metrics(),
                        control
                );
    }
}
