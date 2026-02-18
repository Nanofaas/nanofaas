package it.unimib.datai.nanofaas.controlplane.config;

import java.time.Duration;

/**
 * Holds the runtime-tunable sync-queue defaults.
 * When the sync-queue module is not loaded, {@link #defaults()} provides safe values.
 * When loaded, {@code SyncQueueConfiguration} creates an instance from {@code SyncQueueProperties}.
 */
public record SyncQueueRuntimeDefaults(
        boolean enabled,
        boolean admissionEnabled,
        Duration maxEstimatedWait,
        Duration maxQueueWait,
        int retryAfterSeconds
) {

    /**
     * Returns safe defaults when the sync-queue module is not loaded.
     */
    public static SyncQueueRuntimeDefaults defaults() {
        return new SyncQueueRuntimeDefaults(
                false,
                false,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                2
        );
    }
}
