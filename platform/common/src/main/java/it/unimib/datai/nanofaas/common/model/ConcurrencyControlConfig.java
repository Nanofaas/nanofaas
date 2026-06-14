package it.unimib.datai.nanofaas.common.model;

public record ConcurrencyControlConfig(
        ConcurrencyControlMode mode,
        Integer targetInFlightPerPod,
        Integer minTargetInFlightPerPod,
        Integer maxTargetInFlightPerPod,
        Long upscaleCooldownMs,
        Long downscaleCooldownMs,
        Double highLoadThreshold,
        Double lowLoadThreshold
) {
}
