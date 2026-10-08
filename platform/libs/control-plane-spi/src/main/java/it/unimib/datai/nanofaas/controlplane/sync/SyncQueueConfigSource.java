package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;

import java.time.Duration;

/**
 * Provides sync-queue runtime configuration values.
 * The runtime-config module supplies a dynamic implementation;
 * without it a fixed implementation backed by {@link SyncQueueRuntimeDefaults} is used.
 */
public interface SyncQueueConfigSource {

    boolean syncQueueEnabled();

    boolean syncQueueAdmissionEnabled();

    Duration syncQueueMaxEstimatedWait();

    Duration syncQueueMaxQueueWait();

    int syncQueueRetryAfterSeconds();

    /**
     * The whole runtime-tunable set as one immutable snapshot.
     *
     * <p>Consumers that read more than one correlated field (admission reads
     * {@code admissionEnabled} together with {@code maxEstimatedWait}) must read them
     * through this method: a source that applies a patch atomically can then serve one
     * published combination, so admission never decides on a partial value set. The
     * default composes the per-field getters, which is exactly one published combination
     * for immutable sources; mutable sources override it to return their atomic record.
     */
    default SyncQueueRuntimeDefaults syncQueueRuntimeDefaults() {
        return new SyncQueueRuntimeDefaults(
                syncQueueEnabled(),
                syncQueueAdmissionEnabled(),
                syncQueueMaxEstimatedWait(),
                syncQueueMaxQueueWait(),
                syncQueueRetryAfterSeconds());
    }

    static SyncQueueConfigSource fixed(SyncQueueRuntimeDefaults defaults) {
        return new SyncQueueConfigSource() {
            @Override
            public boolean syncQueueEnabled() {
                return defaults.enabled();
            }

            @Override
            public boolean syncQueueAdmissionEnabled() {
                return defaults.admissionEnabled();
            }

            @Override
            public Duration syncQueueMaxEstimatedWait() {
                return defaults.maxEstimatedWait();
            }

            @Override
            public Duration syncQueueMaxQueueWait() {
                return defaults.maxQueueWait();
            }

            @Override
            public int syncQueueRetryAfterSeconds() {
                return defaults.retryAfterSeconds();
            }
        };
    }
}
