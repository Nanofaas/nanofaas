package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Predicts the next interval's arrival rate, so a limit can be computed rather than searched for.
 *
 * <p>The reason this exists is a measured failure. A controller that chose its limit by stepping
 * and comparing — better than last time, carry on; worse, turn round — cannot tell "my move helped"
 * from "the load fell". Under a load that rises and falls it credited a trough's natural relief to
 * its own decision to shrink, and was still holding the small limit when the load came back, which
 * cost 36,181 rejected requests against 32 for the mode it was meant to improve on. Comparing two
 * moments is only valid when the world holds still between them, and it does not.</p>
 *
 * <p>With a prediction the controller stops comparing moments and starts computing from a model:
 * Little's law turns an arrival rate and a service time straight into the concurrency needed to
 * sustain them. Feedforward rather than feedback, and the attribution problem disappears with the
 * comparison that caused it.</p>
 *
 * <p>Holt's linear method with a damped trend, because the alternative is worse exactly where it
 * matters. A moving average lags a ramp, and a ramp is where the queue builds — by the time a lagged
 * estimate notices, the buffer is already full. The trend term sees the slope; the damping keeps it
 * from carrying that slope through the top of the ramp, where an undamped extrapolation overshoots
 * hardest.</p>
 */
final class LoadForecast {

    /** How much of the newest reading enters the level. */
    private static final double LEVEL_WEIGHT = 0.5;
    /** How much of the newest change enters the trend. */
    private static final double TREND_WEIGHT = 0.3;
    /**
     * How much of the trend is carried into the next step. Below 1 because load here turns: a
     * sawtooth's ramp ends, and an undamped trend predicts a peak that never arrives.
     */
    private static final double DAMPING = 0.8;

    private record State(double level, double trend) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * @param observedRps the rate just measured
     * @return the rate expected next, never negative
     */
    double next(String functionName, double observedRps) {
        State updated = states.compute(functionName, (name, previous) -> {
            if (previous == null) {
                // One reading is a level and no trend: a slope needs two points, and inventing one
                // would have the first interval of every function predicting a ramp.
                return new State(observedRps, 0);
            }
            double projected = previous.level() + DAMPING * previous.trend();
            double level = LEVEL_WEIGHT * observedRps + (1 - LEVEL_WEIGHT) * projected;
            double trend = TREND_WEIGHT * (level - previous.level())
                    + (1 - TREND_WEIGHT) * DAMPING * previous.trend();
            return new State(level, trend);
        });
        return Math.max(0.0, updated.level() + DAMPING * updated.trend());
    }

    void removeFunctionState(String functionName) {
        states.remove(functionName);
    }
}
