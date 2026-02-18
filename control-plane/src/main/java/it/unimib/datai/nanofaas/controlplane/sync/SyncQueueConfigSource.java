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
