package it.unimib.datai.nanofaas.modules.autoscaler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent scale-up events to infer cold starts when the runtime
 * does not report them via headers (e.g., old runtime images).
 */
public class ColdStartTracker {
    private static final long EXPIRY_MS = 30_000;

    private final Map<String, ScaleUpEvent> recentScaleUps = new ConcurrentHashMap<>();

    public void recordScaleUp(String functionName, int fromReplicas, int toReplicas) {
        recentScaleUps.put(functionName, new ScaleUpEvent(fromReplicas, toReplicas, Instant.now()));
    }

    /**
     * Returns true if the function recently scaled up from 0 replicas
     * (within the expiry window), indicating a likely cold start.
     */
    public boolean isPotentialColdStart(String functionName) {
        ScaleUpEvent event = recentScaleUps.get(functionName);
        if (event == null) {
            return false;
        }
        long ageMs = Instant.now().toEpochMilli() - event.timestamp().toEpochMilli();
        if (ageMs > EXPIRY_MS) {
            recentScaleUps.remove(functionName);
            return false;
        }
        return event.fromReplicas() == 0;
    }

    private record ScaleUpEvent(int fromReplicas, int toReplicas, Instant timestamp) {}
}
