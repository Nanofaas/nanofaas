package it.unimib.datai.nanofaas.modules.autoscaler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks whether a commanded scale-up is actually making progress — i.e. whether the ready
 * replicas are advancing toward the already-requested target.
 *
 * <p>The autoscaler must not walk back a higher target while a rollout is still catching up
 * (see {@code InternalScalerDesiredVsReadyRegressionTest}), but that protection cannot be
 * open-ended: if some replicas never become ready (a stuck or failed rollout), a real
 * downscale must not be blocked forever, and a phantom target must not be held indefinitely.
 * This tracker decides when "still catching up" has become "stuck": once the ready count has
 * not advanced toward the requested target for a full progress window, the rollout is
 * declared non-progressing and the scaler may reconcile the target down.</p>
 */
public final class ScalingProgressTracker {
    static final long PROGRESS_WINDOW_MS = 60_000;

    private final Map<String, Observation> observations = new ConcurrentHashMap<>();

    /**
     * Records the current rollout state and returns {@code true} when the rollout is stuck:
     * the requested target is still not reached ({@code ready < requested}) and the ready
     * count has not advanced for at least {@link #PROGRESS_WINDOW_MS}.
     */
    public boolean isStuck(String functionName, int requested, int ready, Instant now) {
        if (ready >= requested) {
            // Rollout complete (or over-requested); there is nothing to reconcile.
            observations.remove(functionName);
            return false;
        }
        Observation previous = observations.get(functionName);
        if (previous == null || previous.requested() != requested || ready > previous.ready()) {
            // First sight of this target, a newly commanded target, or the ready count has
            // advanced since the last look — all count as progress.
            observations.put(functionName, new Observation(requested, ready, now));
            return false;
        }
        // Same target, ready count has not advanced: stuck once the window has elapsed.
        return now.toEpochMilli() - previous.since().toEpochMilli() >= PROGRESS_WINDOW_MS;
    }

    public void clear(String functionName) {
        observations.remove(functionName);
    }

    private record Observation(int requested, int ready, Instant since) {
    }
}
