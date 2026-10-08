package it.unimib.datai.nanofaas.common.model;

import java.util.Optional;

/**
 * Per-function concurrency control.
 *
 * <p>The first block of fields belongs to {@code STATIC_PER_POD} and {@code ADAPTIVE_PER_POD}; the
 * last two belong to {@code BUDGETED}, which states what the function needs rather than how its
 * controller should step.</p>
 *
 * @param targetLatencyMs the service-time SLO this function is held to. {@code BUDGETED} sizes the
 *                        limit to meet it, so it is the objective rather than a threshold on a
 *                        ratio: a number an operator can take from a service agreement instead of
 *                        deriving from the controller's internals.
 * @param weight          the function's claim on the shared budget when there is not enough to
 *                        satisfy everyone. Only relative values matter. Left null it is 1, so a
 *                        platform that never sets weights gets an equal split, which is the right
 *                        default for a knob whose absence should not privilege anyone.
 */
public record ConcurrencyControlConfig(
        ConcurrencyControlMode mode,
        Integer targetInFlightPerPod,
        Integer minTargetInFlightPerPod,
        Integer maxTargetInFlightPerPod,
        Long upscaleCooldownMs,
        Long downscaleCooldownMs,
        Double highLoadThreshold,
        Double lowLoadThreshold,
        Long targetLatencyMs,
        Double weight
) {
    private static final int DEFAULT_TARGET_PER_POD = 2;
    private static final int DEFAULT_MIN_TARGET_PER_POD = 1;
    private static final int DEFAULT_MAX_TARGET_PER_POD = 8;
    private static final long DEFAULT_UPSCALE_COOLDOWN_MS = 30_000L;
    private static final long DEFAULT_DOWNSCALE_COOLDOWN_MS = 60_000L;
    // Fractions of latency degradation over the function's best observed service time:
    // back off above 2x, grow again below ~1.18x. See the concurrency-control module.
    private static final double DEFAULT_HIGH_LOAD_THRESHOLD = 0.5;
    private static final double DEFAULT_LOW_LOAD_THRESHOLD = 0.15;
    // A generic service-time SLO for a function that did not state one. Deliberately not
    // derived from anything the platform measures: a target the controller inferred from
    // observed latency would move whenever the function got slower, which is the one thing
    // an SLO must not do.
    private static final long DEFAULT_TARGET_LATENCY_MS = 250L;
    private static final double DEFAULT_WEIGHT = 1.0;

    public ConcurrencyControlConfig(
            ConcurrencyControlMode mode,
            Integer targetInFlightPerPod,
            Integer minTargetInFlightPerPod,
            Integer maxTargetInFlightPerPod,
            Long upscaleCooldownMs,
            Long downscaleCooldownMs,
            Double highLoadThreshold,
            Double lowLoadThreshold
    ) {
        this(mode, targetInFlightPerPod, minTargetInFlightPerPod, maxTargetInFlightPerPod,
                upscaleCooldownMs, downscaleCooldownMs, highLoadThreshold, lowLoadThreshold,
                null, null);
    }

    /**
     * Normalizes a partially-specified concurrency control config, filling the per-mode defaults
     * that a controller needs but an operator is allowed to omit.
     */
    public static ConcurrencyControlConfig normalize(ConcurrencyControlConfig config) {
        if (config == null || config.mode() == null || config.mode() == ConcurrencyControlMode.FIXED) {
            return new ConcurrencyControlConfig(
                    ConcurrencyControlMode.FIXED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        if (config.mode() == ConcurrencyControlMode.BUDGETED) {
            return normalizeBudgeted(config);
        }
        if (config.mode() == ConcurrencyControlMode.SOJOURN) {
            return normalizeSojourn(config);
        }

        int min = Optional.ofNullable(config.minTargetInFlightPerPod())
                .map(v -> Math.max(1, v))
                .orElse(DEFAULT_MIN_TARGET_PER_POD);
        int max = Optional.ofNullable(config.maxTargetInFlightPerPod())
                .map(v -> Math.max(1, v))
                .orElse(DEFAULT_MAX_TARGET_PER_POD);
        if (min > max) {
            min = max;
        }

        int target = Optional.ofNullable(config.targetInFlightPerPod()).orElse(DEFAULT_TARGET_PER_POD);
        target = Math.clamp(target, min, max);

        return new ConcurrencyControlConfig(
                config.mode(),
                target,
                min,
                max,
                Optional.ofNullable(config.upscaleCooldownMs()).orElse(DEFAULT_UPSCALE_COOLDOWN_MS),
                Optional.ofNullable(config.downscaleCooldownMs()).orElse(DEFAULT_DOWNSCALE_COOLDOWN_MS),
                Optional.ofNullable(config.highLoadThreshold()).orElse(DEFAULT_HIGH_LOAD_THRESHOLD),
                Optional.ofNullable(config.lowLoadThreshold()).orElse(DEFAULT_LOW_LOAD_THRESHOLD)
        );
    }

    /**
     * SOJOURN searches for a minimum rather than stepping towards a per-replica target, so the
     * target and the gradient thresholds are left null. Its {@code targetLatencyMs} is an
     * end-to-end promise rather than a service-time one, and the weight is unused: the mode governs
     * one function at a time and has no budget to divide.
     */
    private static ConcurrencyControlConfig normalizeSojourn(ConcurrencyControlConfig config) {
        long targetLatencyMs = Optional.ofNullable(config.targetLatencyMs())
                .filter(value -> value > 0)
                .orElse(DEFAULT_TARGET_LATENCY_MS);
        int min = Optional.ofNullable(config.minTargetInFlightPerPod())
                .map(value -> Math.max(1, value))
                .orElse(1);
        Integer max = config.maxTargetInFlightPerPod() == null
                ? null
                : Math.max(min, config.maxTargetInFlightPerPod());
        return new ConcurrencyControlConfig(
                ConcurrencyControlMode.SOJOURN,
                null,
                min,
                max,
                null,
                null,
                null,
                null,
                targetLatencyMs,
                null
        );
    }

    /**
     * BUDGETED states what the function needs, not how its controller steps, so the per-replica
     * target and the gradient thresholds are left null rather than filled with values that would
     * read as configuration nobody set.
     */
    private static ConcurrencyControlConfig normalizeBudgeted(ConcurrencyControlConfig config) {
        long targetLatencyMs = Optional.ofNullable(config.targetLatencyMs())
                .filter(value -> value > 0)
                .orElse(DEFAULT_TARGET_LATENCY_MS);
        double weight = Optional.ofNullable(config.weight())
                .filter(value -> value > 0)
                .orElse(DEFAULT_WEIGHT);
        int min = Optional.ofNullable(config.minTargetInFlightPerPod())
                .map(value -> Math.max(1, value))
                .orElse(1);
        Integer max = config.maxTargetInFlightPerPod() == null
                ? null
                : Math.max(min, config.maxTargetInFlightPerPod());
        return new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED,
                null,
                min,
                max,
                null,
                null,
                null,
                null,
                targetLatencyMs,
                weight
        );
    }
}
