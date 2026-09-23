package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime-mutable {@link SyncQueueConfigSource} backed by the runtime-config admin API.
 *
 * <p>The five correlated settings are published as one immutable
 * {@link SyncQueueRuntimeDefaults} snapshot behind a single volatile reference, so a
 * concurrent reader (admission, queue timeout checks, the routing decision in the core
 * coordinator) never observes a partial combination of values mid-{@link #apply}/{@link
 * #restore}. A failed apply/restore leaves the previously published snapshot untouched:
 * no field is written until every value has been read and validated into the new record.
 */
public final class MutableSyncQueueConfigSource implements SyncQueueConfigSource {
    // One spelling of each runtime key, shared with the validator: they were written
    // out five times across two files, where a typo changes a key silently.
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ADMISSION_ENABLED = "admissionEnabled";
    public static final String KEY_MAX_ESTIMATED_WAIT = "maxEstimatedWait";
    public static final String KEY_MAX_QUEUE_WAIT = "maxQueueWait";
    public static final String KEY_RETRY_AFTER_SECONDS = "retryAfterSeconds";

    private volatile SyncQueueRuntimeDefaults settings; // NOSONAR (java:S3077): thread-safe or immutable value replaced wholesale

    public MutableSyncQueueConfigSource(SyncQueueProperties props) {
        this.settings = props.runtimeDefaults();
    }

    @Override public boolean syncQueueEnabled() { return settings.enabled(); }
    @Override public boolean syncQueueAdmissionEnabled() { return settings.admissionEnabled(); }
    @Override public Duration syncQueueMaxEstimatedWait() { return settings.maxEstimatedWait(); }
    @Override public Duration syncQueueMaxQueueWait() { return settings.maxQueueWait(); }
    @Override public int syncQueueRetryAfterSeconds() { return settings.retryAfterSeconds(); }

    @Override
    public SyncQueueRuntimeDefaults syncQueueRuntimeDefaults() {
        return settings;
    }

    public Map<String, Object> snapshot() {
        SyncQueueRuntimeDefaults current = settings;
        return Map.of(KEY_ENABLED, current.enabled(), KEY_ADMISSION_ENABLED, current.admissionEnabled(),
                KEY_MAX_ESTIMATED_WAIT, current.maxEstimatedWait().toString(),
                KEY_MAX_QUEUE_WAIT, current.maxQueueWait().toString(),
                KEY_RETRY_AFTER_SECONDS, current.retryAfterSeconds());
    }

    public void apply(Map<String, Object> values) {
        Map<String, Object> merged = new HashMap<>(snapshot());
        merged.putAll(values);
        restore(merged);
    }

    public void restore(Map<String, Object> values) {
        // Read and validate every field into locals BEFORE publishing, so a malformed
        // or partial map throws with the previously published snapshot still intact.
        boolean enabled = (Boolean) values.get(KEY_ENABLED);
        boolean admissionEnabled = (Boolean) values.get(KEY_ADMISSION_ENABLED);
        Duration maxEstimatedWait = Duration.parse((String) values.get(KEY_MAX_ESTIMATED_WAIT));
        Duration maxQueueWait = Duration.parse((String) values.get(KEY_MAX_QUEUE_WAIT));
        int retryAfterSeconds = ((Number) values.get(KEY_RETRY_AFTER_SECONDS)).intValue();
        settings = new SyncQueueRuntimeDefaults(enabled, admissionEnabled,
                maxEstimatedWait, maxQueueWait, retryAfterSeconds);
    }
}
