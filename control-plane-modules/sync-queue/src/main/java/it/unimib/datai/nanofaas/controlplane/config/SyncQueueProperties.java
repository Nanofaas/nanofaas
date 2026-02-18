package it.unimib.datai.nanofaas.controlplane.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "sync-queue")
@Validated
public record SyncQueueProperties(
        boolean enabled,
        boolean admissionEnabled,
        @Positive int maxDepth,
        @NotNull Duration maxEstimatedWait,
        @NotNull Duration maxQueueWait,
        @Positive int retryAfterSeconds,
        @NotNull Duration throughputWindow,
        @Positive int perFunctionMinSamples
) {
    public SyncQueueRuntimeDefaults runtimeDefaults() {
        return new SyncQueueRuntimeDefaults(
                enabled,
                admissionEnabled,
                maxEstimatedWait,
                maxQueueWait,
                retryAfterSeconds
        );
    }
}
