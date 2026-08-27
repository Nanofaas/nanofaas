package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;

import java.time.Duration;
import java.util.Map;

public final class MutableSyncQueueConfigSource implements SyncQueueConfigSource {
    private volatile boolean enabled;
    private volatile boolean admissionEnabled;
    private volatile Duration maxEstimatedWait;
    private volatile Duration maxQueueWait;
    private volatile int retryAfterSeconds;

    public MutableSyncQueueConfigSource(SyncQueueProperties props) {
        restore(Map.of("enabled", props.enabled(), "admissionEnabled", props.admissionEnabled(),
                "maxEstimatedWait", props.maxEstimatedWait().toString(),
                "maxQueueWait", props.maxQueueWait().toString(),
                "retryAfterSeconds", props.retryAfterSeconds()));
    }

    @Override public boolean syncQueueEnabled() { return enabled; }
    @Override public boolean syncQueueAdmissionEnabled() { return admissionEnabled; }
    @Override public Duration syncQueueMaxEstimatedWait() { return maxEstimatedWait; }
    @Override public Duration syncQueueMaxQueueWait() { return maxQueueWait; }
    @Override public int syncQueueRetryAfterSeconds() { return retryAfterSeconds; }

    public Map<String, Object> snapshot() {
        return Map.of("enabled", enabled, "admissionEnabled", admissionEnabled,
                "maxEstimatedWait", maxEstimatedWait.toString(), "maxQueueWait", maxQueueWait.toString(),
                "retryAfterSeconds", retryAfterSeconds);
    }

    public void apply(Map<String, Object> values) {
        Map<String, Object> merged = new java.util.HashMap<>(snapshot());
        merged.putAll(values);
        restore(merged);
    }

    public void restore(Map<String, Object> values) {
        enabled = (Boolean) values.get("enabled");
        admissionEnabled = (Boolean) values.get("admissionEnabled");
        maxEstimatedWait = Duration.parse((String) values.get("maxEstimatedWait"));
        maxQueueWait = Duration.parse((String) values.get("maxQueueWait"));
        retryAfterSeconds = ((Number) values.get("retryAfterSeconds")).intValue();
    }
}
