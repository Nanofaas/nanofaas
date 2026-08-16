package it.unimib.datai.nanofaas.common.model;

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
}
