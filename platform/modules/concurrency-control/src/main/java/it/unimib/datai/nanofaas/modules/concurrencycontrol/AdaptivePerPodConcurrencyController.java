package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latency-gradient controller (TCP-Vegas style): it compares the mean service time observed in the
 * last tick against the best service time seen for the function, and moves the per-replica in-flight
 * target against that degradation.
 *
 * <p>The signal is deliberately <em>not</em> queue depth or utilisation. A function running at full
 * utilisation with an empty queue is healthy, not saturated, so neither of those can locate the point
 * where extra concurrency starts costing throughput. Rising service time at constant work is exactly
 * that point.</p>
 */
public class AdaptivePerPodConcurrencyController {

    /**
     * ponytail: the baseline creeps up 0.2%/tick so a single lucky-fast interval cannot pin it
     * forever, while a real 2x degradation still stands out for minutes. Replace with a windowed
     * minimum if functions turn out to have long-period service-time seasonality.
     */
    private static final double BASELINE_DRIFT_PER_TICK = 1.002;

    private final Map<String, AdaptiveConcurrencyState> states = new ConcurrentHashMap<>();

    /**
     * @param latencyCount   cumulative count of the function's service-time timer
     * @param latencyTotalMs cumulative total of that timer, in milliseconds
     */
    public int computeEffectiveConcurrency(FunctionSpec spec,
                                           int readyReplicas,
                                           long latencyCount,
                                           double latencyTotalMs,
                                           long nowEpochMs) {
        int configured = Math.max(1, spec.concurrency());
        ConcurrencyControlConfig control = spec.scalingConfig() == null
                ? null
                : spec.scalingConfig().concurrencyControl();
        if (control == null || control.mode() != ConcurrencyControlMode.ADAPTIVE_PER_POD) {
            return configured;
        }

        int minTarget = Math.max(1, valueOrDefault(control.minTargetInFlightPerPod(), 1));
        int maxTarget = Math.max(minTarget, valueOrDefault(control.maxTargetInFlightPerPod(), 8));
        int initialTarget = clamp(valueOrDefault(control.targetInFlightPerPod(), 2), minTarget, maxTarget);
        long upCooldown = valueOrDefault(control.upscaleCooldownMs(), 30_000L);
        long downCooldown = valueOrDefault(control.downscaleCooldownMs(), 60_000L);
        double highThreshold = valueOrDefault(control.highLoadThreshold(), 0.5);
        double lowThreshold = valueOrDefault(control.lowLoadThreshold(), 0.15);

        AdaptiveConcurrencyState state = states.computeIfAbsent(
                spec.name(),
                ignored -> new AdaptiveConcurrencyState(initialTarget)
        );

        int target = clamp(state.targetInFlightPerPod(), minTarget, maxTarget);
        double degradation = sampleDegradation(state, latencyCount, latencyTotalMs);

        if (degradation >= 0) {
            if (degradation >= highThreshold) {
                if (nowEpochMs - state.lastDecreaseEpochMs() >= downCooldown) {
                    target = Math.max(minTarget, target - 1);
                    state.lastDecreaseEpochMs(nowEpochMs);
                }
            } else if (degradation <= lowThreshold
                    && nowEpochMs - state.lastIncreaseEpochMs() >= upCooldown) {
                target = Math.min(maxTarget, target + 1);
                state.lastIncreaseEpochMs(nowEpochMs);
            }
        }

        state.targetInFlightPerPod(target);
        int replicas = Math.max(1, readyReplicas);
        long desired = (long) replicas * target;
        int effective = desired > configured ? configured : (int) desired;
        return Math.max(1, effective);
    }

    /**
     * Consumes the timer readings and returns the fraction by which the interval's mean service time
     * exceeds the function's baseline, in {@code [0, 1)}. Returns {@code -1} when the interval carried
     * no completed invocations, i.e. there is nothing to learn from and the target must not move.
     */
    private static double sampleDegradation(AdaptiveConcurrencyState state, long latencyCount, double latencyTotalMs) {
        long deltaCount = latencyCount - state.lastLatencyCount();
        double deltaTotalMs = latencyTotalMs - state.lastLatencyTotalMs();
        state.lastLatencyCount(latencyCount);
        state.lastLatencyTotalMs(latencyTotalMs);
        if (deltaCount <= 0 || deltaTotalMs <= 0) {
            // No traffic, or the meters were recreated under us; the readings above re-baseline it.
            return -1;
        }

        double meanMs = deltaTotalMs / deltaCount;
        double baselineMs = state.baselineLatencyMs();
        baselineMs = baselineMs <= 0 ? meanMs : Math.min(meanMs, baselineMs * BASELINE_DRIFT_PER_TICK);
        state.baselineLatencyMs(baselineMs);
        return (meanMs - baselineMs) / meanMs;
    }

    public int currentTargetInFlightPerPod(String functionName, int fallback) {
        AdaptiveConcurrencyState state = states.get(functionName);
        if (state == null) {
            return fallback;
        }
        return state.targetInFlightPerPod();
    }

    void removeFunctionState(String functionName) {
        states.remove(functionName);
    }

    private static int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long valueOrDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static double valueOrDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }
}
