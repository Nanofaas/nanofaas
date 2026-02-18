package it.unimib.datai.nanofaas.modules.runtimeconfig;

import java.time.Duration;

/**
 * Immutable snapshot of all hot-updatable runtime configuration.
 * A new instance is created on each config update; consumers read the latest
 * snapshot through {@link RuntimeConfigService}.
 */
public record RuntimeConfigSnapshot(
        long revision,
        int rateMaxPerSecond,
        boolean syncQueueEnabled,
        boolean syncQueueAdmissionEnabled,
        Duration syncQueueMaxEstimatedWait,
        Duration syncQueueMaxQueueWait,
        int syncQueueRetryAfterSeconds
) {

    /**
     * Creates a new snapshot with an incremented revision and the given patch applied.
     */
    public RuntimeConfigSnapshot applyPatch(RuntimeConfigPatch patch) {
        return new RuntimeConfigSnapshot(
                revision + 1,
                patch.rateMaxPerSecond() != null ? patch.rateMaxPerSecond() : rateMaxPerSecond,
                patch.syncQueueEnabled() != null ? patch.syncQueueEnabled() : syncQueueEnabled,
                patch.syncQueueAdmissionEnabled() != null ? patch.syncQueueAdmissionEnabled() : syncQueueAdmissionEnabled,
                patch.syncQueueMaxEstimatedWait() != null ? patch.syncQueueMaxEstimatedWait() : syncQueueMaxEstimatedWait,
                patch.syncQueueMaxQueueWait() != null ? patch.syncQueueMaxQueueWait() : syncQueueMaxQueueWait,
                patch.syncQueueRetryAfterSeconds() != null ? patch.syncQueueRetryAfterSeconds() : syncQueueRetryAfterSeconds
        );
    }
}
