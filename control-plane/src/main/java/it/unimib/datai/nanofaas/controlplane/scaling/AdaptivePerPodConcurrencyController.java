package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdaptivePerPodConcurrencyController {
    private final Map<String, AdaptiveConcurrencyState> states = new ConcurrentHashMap<>();

    public int computeEffectiveConcurrency(FunctionSpec spec,
                                           int readyReplicas,
                                           double loadRatio,
                                           boolean downscaleSignal,
                                           boolean atMaxReplicas,
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
        double highThreshold = valueOrDefault(control.highLoadThreshold(), 0.85);
        double lowThreshold = valueOrDefault(control.lowLoadThreshold(), 0.35);

        AdaptiveConcurrencyState state = states.computeIfAbsent(
                spec.name(),
                ignored -> new AdaptiveConcurrencyState(initialTarget)
        );

        int target = clamp(state.targetInFlightPerPod(), minTarget, maxTarget);

        if (downscaleSignal) {
            state.lastReplicaDownEpochMs(nowEpochMs);
        }

        if (atMaxReplicas && loadRatio >= highThreshold) {
            if (nowEpochMs - state.lastDecreaseEpochMs() >= downCooldown) {
                target = Math.max(minTarget, target - 1);
                state.lastDecreaseEpochMs(nowEpochMs);
            }
        } else if (loadRatio <= lowThreshold) {
            boolean recentlyScaledDown = nowEpochMs - state.lastReplicaDownEpochMs() < downCooldown;
            if (!recentlyScaledDown && nowEpochMs - state.lastIncreaseEpochMs() >= upCooldown) {
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

    public int currentTargetInFlightPerPod(String functionName, int fallback) {
        AdaptiveConcurrencyState state = states.get(functionName);
        if (state == null) {
            return fallback;
        }
        return state.targetInFlightPerPod();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
