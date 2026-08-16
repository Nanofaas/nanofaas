package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a cumulative service-time timer into the mean of the interval just ended.
 *
 * <p>The cumulative value answers "since startup", which flattens exactly the change a controller
 * reacts to: a function that has served a million fast requests and is now slow still reads fast.
 * Only the difference between two readings describes the present.</p>
 */
final class LatencyIntervals {
    private record Reading(long count, double totalMs, long atEpochMs) {
    }

    /**
     * What one interval looked like.
     *
     * @param meanLatencyMs mean service time, or 0 when nothing completed
     * @param throughputRps completions per second, or 0 when nothing completed
     */
    record Interval(double meanLatencyMs, double throughputRps) {
        static final Interval EMPTY = new Interval(0, 0);
    }

    private final Map<String, Reading> previous = new ConcurrentHashMap<>();

    Interval sample(String functionName, long count, double totalMs, long nowEpochMs) {
        Reading last = previous.put(functionName, new Reading(count, totalMs, nowEpochMs));
        if (last == null) {
            return Interval.EMPTY;
        }
        long deltaCount = count - last.count();
        double deltaTotal = totalMs - last.totalMs();
        long deltaMs = nowEpochMs - last.atEpochMs();
        if (deltaCount <= 0 || deltaTotal <= 0 || deltaMs <= 0) {
            return Interval.EMPTY;
        }
        return new Interval(deltaTotal / deltaCount, deltaCount * 1000.0 / deltaMs);
    }

    void removeFunctionState(String functionName) {
        previous.remove(functionName);
    }
}
