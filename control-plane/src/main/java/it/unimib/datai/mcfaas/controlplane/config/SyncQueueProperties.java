package it.unimib.datai.mcfaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "sync-queue")
@Validated
public record SyncQueueProperties(
        boolean enabled,
        boolean admissionEnabled,
        int maxDepth,
        Duration maxEstimatedWait,
        Duration maxQueueWait,
        int retryAfterSeconds,
        Duration throughputWindow,
        int perFunctionMinSamples
) {
}
