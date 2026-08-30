package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;

import java.time.Duration;
import java.util.Map;

public final class MutableSyncQueueConfigSource implements SyncQueueConfigSource {
    // One spelling of each runtime key, shared with the validator: they were written
    // out five times across two files, where a typo changes a key silently.
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ADMISSION_ENABLED = "admissionEnabled";
    public static final String KEY_MAX_ESTIMATED_WAIT = "maxEstimatedWait";
    public static final String KEY_MAX_QUEUE_WAIT = "maxQueueWait";
    public static final String KEY_RETRY_AFTER_SECONDS = "retryAfterSeconds";

    private volatile boolean enabled;
    private volatile boolean admissionEnabled;
    private volatile Duration maxEstimatedWait;
    private volatile Duration maxQueueWait;
    private volatile int retryAfterSeconds;

    public MutableSyncQueueConfigSource(SyncQueueProperties props) {
        restore(Map.of(KEY_ENABLED, props.enabled(), KEY_ADMISSION_ENABLED, props.admissionEnabled(),
                KEY_MAX_ESTIMATED_WAIT, props.maxEstimatedWait().toString(),
                KEY_MAX_QUEUE_WAIT, props.maxQueueWait().toString(),
                KEY_RETRY_AFTER_SECONDS, props.retryAfterSeconds()));
    }

    @Override public boolean syncQueueEnabled() { return enabled; }
    @Override public boolean syncQueueAdmissionEnabled() { return admissionEnabled; }
    @Override public Duration syncQueueMaxEstimatedWait() { return maxEstimatedWait; }
    @Override public Duration syncQueueMaxQueueWait() { return maxQueueWait; }
    @Override public int syncQueueRetryAfterSeconds() { return retryAfterSeconds; }

    public Map<String, Object> snapshot() {
        return Map.of(KEY_ENABLED, enabled, KEY_ADMISSION_ENABLED, admissionEnabled,
                KEY_MAX_ESTIMATED_WAIT, maxEstimatedWait.toString(), KEY_MAX_QUEUE_WAIT, maxQueueWait.toString(),
                KEY_RETRY_AFTER_SECONDS, retryAfterSeconds);
    }

    public void apply(Map<String, Object> values) {
        Map<String, Object> merged = new java.util.HashMap<>(snapshot());
        merged.putAll(values);
        restore(merged);
    }

    public void restore(Map<String, Object> values) {
        enabled = (Boolean) values.get(KEY_ENABLED);
        admissionEnabled = (Boolean) values.get(KEY_ADMISSION_ENABLED);
        maxEstimatedWait = Duration.parse((String) values.get(KEY_MAX_ESTIMATED_WAIT));
        maxQueueWait = Duration.parse((String) values.get(KEY_MAX_QUEUE_WAIT));
        retryAfterSeconds = ((Number) values.get(KEY_RETRY_AFTER_SECONDS)).intValue();
    }
}
