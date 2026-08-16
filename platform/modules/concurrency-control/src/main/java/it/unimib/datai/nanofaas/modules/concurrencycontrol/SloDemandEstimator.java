package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much concurrency a function needs to serve its load within its latency SLO.
 *
 * <p>The rule is the gradient from Netflix's `concurrency-limits` (the `Gradient2` limiter), with
 * the function's SLO as the target rather than a self-measured minimum:</p>
 *
 * <pre>
 *   gradient = clamp(targetLatency / observedLatency, 0.5, 1.0)
 *   desired  = limit x gradient + sqrt(limit)
 * </pre>
 *
 * <p>Two properties are why this shape and not the latency gradient the other adaptive mode uses.</p>
 *
 * <p>The target is an SLO, not the best latency ever seen. A controller that chases its own minimum
 * has no interior optimum to find — service time rises with concurrency on any shared resource, so
 * the gradient points downhill everywhere and the limit walks to its floor, which is what two
 * runtimes were measured doing. Against a fixed target the gradient is above 1 while the function
 * is inside its SLO and below it only when the promise is actually being broken.</p>
 *
 * <p>The {@code sqrt(limit)} term is deliberate slack, and it is what keeps rejections down. A limit
 * sized exactly to the current arrival rate leaves nothing for a burst, so the next arrival above
 * the mean queues and, if the queue is full, is rejected. Little's law says the concurrency needed
 * to absorb arrival rate λ at service time S is λS; the headroom is the margin over that, and it
 * grows sublinearly so a large limit does not carry proportionally large waste.</p>
 *
 * <p>The gradient alone is not enough, and the reason is measured rather than theoretical. An
 * SLO-driven controller spends every millisecond of latency it is given: told it may take 10ms, it
 * grew to four times the concurrency of the gradient mode on the same load and delivered 6% fewer
 * requests, because nothing in the rule asked whether the extra concurrency bought any
 * completions. So growth is also capped at the knee, computed rather than hunted:</p>
 *
 * <pre>
 *   knee = λ_max x S_min      (Little's law: the concurrency that saturates service)
 * </pre>
 *
 * <p>Once throughput saturates, λ_max stops rising and the cap stops rising with it, whatever the
 * SLO would still allow. This is BBR's shape rather than Vegas': track the two extremes separately
 * and target their product, instead of reading one degraded signal.</p>
 */
public class SloDemandEstimator {

    /**
     * Floor on the gradient. Without it a single slow interval — a cold start, a GC pause — would
     * halve the limit repeatedly; with it the worst case is halving per tick, which recovers.
     */
    private static final double MIN_GRADIENT = 0.5;

    /**
     * How fast a remembered extreme is allowed to go stale, per tick. An all-time minimum service
     * time never recovers from one lucky interval and an all-time peak throughput never admits the
     * machine got slower, so both are nudged back towards the present. A window with expiry, as
     * BBR uses, would be more faithful; this is the cheap version of the same intent.
     */
    private static final double EXTREME_DECAY = 1.01;

    private final Map<String, Integer> limits = new ConcurrentHashMap<>();
    private final Map<String, Double> bestLatencyMs = new ConcurrentHashMap<>();
    private final Map<String, Double> bestThroughputRps = new ConcurrentHashMap<>();

    /**
     * @param observedLatencyMs mean service time of the interval, or a non-positive value when
     *                          nothing completed — in which case the function is told to keep what
     *                          it has, since an idle interval is evidence of nothing.
     */
    public ConcurrencyDemand estimate(
            FunctionSpec spec, double observedLatencyMs, double throughputRps, int inFlight) {
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
        double weight = control == null || control.weight() == null ? 1.0 : control.weight();
        long targetLatencyMs = control == null || control.targetLatencyMs() == null
                ? 0L
                : control.targetLatencyMs();

        int current = limits.getOrDefault(spec.name(), Math.clamp(inFlight + 1L, floor, ceiling));
        int desired = current;
        if (observedLatencyMs > 0 && targetLatencyMs > 0) {
            double gradient = Math.clamp(targetLatencyMs / observedLatencyMs, MIN_GRADIENT, 1.0);
            desired = (int) Math.round(current * gradient + Math.sqrt(current));
            // Growth stops at the knee even when the SLO would allow more. Shrinking is never
            // capped: an SLO breach is a broken promise, and no throughput argument outranks it.
            if (desired > current) {
                desired = Math.min(desired, knee(spec.name(), observedLatencyMs, throughputRps));
            }
        }
        desired = Math.clamp(desired, floor, ceiling);
        limits.put(spec.name(), desired);
        return new ConcurrencyDemand(spec.name(), desired, floor, weight);
    }

    /**
     * Little's law on the best the function has been seen to manage: the concurrency at which
     * service is saturated and anything further is queueing rather than work.
     */
    private int knee(String functionName, double observedLatencyMs, double throughputRps) {
        double bestLatency = bestLatencyMs.merge(
                functionName, observedLatencyMs,
                (remembered, observed) -> Math.min(observed, remembered * EXTREME_DECAY));
        double bestThroughput = bestThroughputRps.merge(
                functionName, throughputRps,
                (remembered, observed) -> Math.max(observed, remembered / EXTREME_DECAY));
        if (bestThroughput <= 0 || bestLatency <= 0) {
            return Integer.MAX_VALUE;
        }
        double concurrency = bestThroughput * bestLatency / 1000.0;
        // The same burst slack as the gradient rule, for the same reason.
        return (int) Math.ceil(concurrency + Math.sqrt(Math.max(1.0, concurrency)));
    }

    /**
     * Records what the function was actually granted, so the next estimate steps from the limit in
     * force rather than from the one it asked for. Without this a function starved by the budget
     * would keep asking from an imaginary position and jump when capacity freed up.
     */
    public void recordGrant(String functionName, int granted) {
        limits.put(functionName, Math.max(1, granted));
    }

    void removeFunctionState(String functionName) {
        limits.remove(functionName);
        bestLatencyMs.remove(functionName);
        bestThroughputRps.remove(functionName);
    }
}
