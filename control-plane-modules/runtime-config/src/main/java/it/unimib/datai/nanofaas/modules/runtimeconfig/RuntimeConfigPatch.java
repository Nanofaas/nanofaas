package it.unimib.datai.nanofaas.modules.runtimeconfig;

import java.time.Duration;

/**
 * Partial update request for runtime configuration.
 * Null fields are left unchanged.
 */
public record RuntimeConfigPatch(
        Integer rateMaxPerSecond,
        Boolean syncQueueEnabled,
        Boolean syncQueueAdmissionEnabled,
        Duration syncQueueMaxEstimatedWait,
        Duration syncQueueMaxQueueWait,
        Integer syncQueueRetryAfterSeconds
) {
}
