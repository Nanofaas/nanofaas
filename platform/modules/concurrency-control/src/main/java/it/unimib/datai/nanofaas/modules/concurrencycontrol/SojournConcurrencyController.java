package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the limit that minimises how long a caller spends in the system.
 *
 * <p>The other controllers decide from service time. That is the wrong quantity and the reason is
 * measured rather than argued: under queueing the mean wait was 37-43ms against a service time near
 * 5ms, so service time is about a seventh of what the caller experiences, and the missing
 * six-sevenths is produced by the limit the controller itself chose. One mode was observed cutting
 * service time by 31% and 44%, both far inside their target, while making end-to-end p95 worse.</p>
 *
 * <p><strong>Why a search and not a threshold.</strong> The obvious repair — feed end-to-end latency
 * into the existing gradient rule — is unstable, and it is worth stating why so nobody tries it
 * again. That rule shrinks the limit when latency exceeds the target; a smaller limit serves fewer
 * requests at once, so the queue drains more slowly and the wait grows, which makes the rule shrink
 * again. The loop runs away to the floor.</p>
 *
 * <p>Sojourn time is not monotone in the limit — the wait falls as the limit rises while the service
 * time climbs — so it has an interior minimum, which is exactly what service time lacks. A minimum
 * cannot be found by a threshold, only by moving and looking. So this controller steps the limit,
 * compares the sojourn it got with the sojourn it had, keeps going while that helps and reverses
 * when it stops. The point it settles on is the knee, discovered rather than computed from Little's
 * law — which also removes the estimate the other modes have to make.</p>
 *
 * <p>Inside the target the limit is left alone. Continuously hunting for a minimum nobody asked for
 * costs churn in the enforcing queue for latency the caller was already promised, and a controller
 * with a stable rest state is one an operator can reason about.</p>
 */
public class SojournConcurrencyController {

    /**
     * How much better or worse a reading has to be before it counts as a direction. Two
     * measurements of the same operating point differ by ordinary variance, and without a dead band
     * the controller reads that variance as a gradient and oscillates forever.
     */
    private static final double DEAD_BAND = 0.05;

    /**
     * One step at a time. A larger step converges sooner and overshoots the minimum it is looking
     * for, and overshoot here is paid by the callers whose requests are in flight at the time.
     */
    private static final int STEP = 1;

    private final Map<String, Search> searches = new ConcurrentHashMap<>();
    private final LatencyIntervals sojournIntervals;

    public SojournConcurrencyController() {
        this(new LatencyIntervals());
    }

    SojournConcurrencyController(LatencyIntervals sojournIntervals) {
        this.sojournIntervals = sojournIntervals;
    }

    /** What one function's search knows between ticks. */
    private static final class Search {
        private int limit;
        private double lastSojournMs;
        private int direction = 1;

        private Search(int limit) {
            this.limit = limit;
            this.lastSojournMs = 0;
        }
    }

    /**
     * One function's inputs for a tick.
     *
     * @param e2eCount   cumulative count of the end-to-end timer, which spans enqueue to completion
     * @param e2eTotalMs cumulative total of that timer
     */
    public record FunctionObservation(
            FunctionSpec spec,
            int inFlight,
            long e2eCount,
            double e2eTotalMs
    ) {
    }

    /**
     * @return the limit granted, so a caller can log or assert on it without reading it back
     */
    public int apply(
            FunctionObservation observation, ScalingMetricsSource metricsSource, long nowEpochMs) {
        FunctionSpec spec = observation.spec();
        Bounds bounds = Bounds.of(spec);
        Search search = searches.computeIfAbsent(
                spec.name(), name -> new Search(Math.clamp(observation.inFlight() + 1L,
                        bounds.floor(), bounds.ceiling())));

        LatencyIntervals.Interval interval = sojournIntervals.sample(
                spec.name(), observation.e2eCount(), observation.e2eTotalMs(), nowEpochMs);
        int limit = decide(search, interval.meanLatencyMs(), bounds);

        metricsSource.setEffectiveConcurrency(spec.name(), limit);
        metricsSource.updateConcurrencyController(spec.name(), ConcurrencyControlMode.SOJOURN, limit);
        return limit;
    }

    private int decide(Search search, double sojournMs, Bounds bounds) {
        search.limit = Math.clamp(search.limit, bounds.floor(), bounds.ceiling());
        // An interval in which nothing completed is evidence of nothing, and treating it as a
        // reading would move the limit on the strength of an absence.
        if (sojournMs <= 0) {
            return search.limit;
        }
        double previous = search.lastSojournMs;
        search.lastSojournMs = sojournMs;
        // Inside the promise: hold. The minimum is somewhere below, but chasing it costs churn for
        // latency the caller was already promised.
        if (bounds.targetMs() > 0 && sojournMs <= bounds.targetMs()) {
            return search.limit;
        }
        if (previous > 0) {
            search.direction = nextDirection(search, sojournMs, previous, bounds);
        }
        search.limit = Math.clamp(search.limit + (long) search.direction * STEP,
                bounds.floor(), bounds.ceiling());
        return search.limit;
    }

    private static int nextDirection(Search search, double sojournMs, double previous, Bounds bounds) {
        double change = (sojournMs - previous) / previous;
        if (change > DEAD_BAND) {
            // The last move made things worse, so the minimum is behind us.
            return -search.direction;
        }
        if (change < -DEAD_BAND) {
            // Still improving. Carry on, including into a bound: resting against the ceiling is
            // the right answer when the ceiling is the best position available, and turning round
            // there would give up throughput on every other tick for nothing.
            return search.direction;
        }
        // Flat. In open ground that is the neighbourhood of the minimum and holding course is
        // fine, but pressed against a bound it means the search has nowhere to go and nothing to
        // learn, so it turns and explores the way it can still move.
        return pressingABound(search, bounds) ? -search.direction : search.direction;
    }

    private static boolean pressingABound(Search search, Bounds bounds) {
        return (search.direction > 0 && search.limit >= bounds.ceiling())
                || (search.direction < 0 && search.limit <= bounds.floor());
    }

    /** The window the search is allowed to move in, and the promise it is holding to. */
    private record Bounds(int floor, int ceiling, long targetMs) {
        static Bounds of(FunctionSpec spec) {
            ConcurrencyControlConfig control = spec.scalingConfig() == null
                    ? null
                    : spec.scalingConfig().concurrencyControl();
            int ceiling = Math.max(1, spec.concurrency());
            int floor = control == null || control.minTargetInFlightPerPod() == null
                    ? 1
                    : Math.max(1, control.minTargetInFlightPerPod());
            if (control != null && control.maxTargetInFlightPerPod() != null) {
                ceiling = Math.clamp(control.maxTargetInFlightPerPod(), floor, ceiling);
            }
            floor = Math.min(floor, ceiling);
            long targetMs = control == null || control.targetLatencyMs() == null
                    ? 0L
                    : control.targetLatencyMs();
            return new Bounds(floor, ceiling, targetMs);
        }
    }

    void removeFunctionState(String functionName) {
        searches.remove(functionName);
        sojournIntervals.removeFunctionState(functionName);
    }
}
