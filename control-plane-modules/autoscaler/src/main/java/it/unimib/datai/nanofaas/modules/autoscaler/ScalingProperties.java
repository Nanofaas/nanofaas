package it.unimib.datai.nanofaas.modules.autoscaler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "nanofaas.scaling")
public record ScalingProperties(
        Long pollIntervalMs,
        Integer defaultMinReplicas,
        Integer defaultMaxReplicas,
        Integer defaultTargetInFlightPerPod,
        Long concurrencyUpscaleCooldownMs,
        Long concurrencyDownscaleCooldownMs,
        Double concurrencyHighLoadThreshold,
        Double concurrencyLowLoadThreshold
) {
    @ConstructorBinding
    public ScalingProperties {
    }

    public ScalingProperties(
            Long pollIntervalMs,
            Integer defaultMinReplicas,
            Integer defaultMaxReplicas
    ) {
        this(
                pollIntervalMs,
                defaultMinReplicas,
                defaultMaxReplicas,
                null,
                null,
                null,
                null,
                null
        );
    }

    public long pollIntervalMsOrDefault() {
        return pollIntervalMs != null && pollIntervalMs > 0 ? pollIntervalMs : 5000;
    }

    public int defaultMinReplicasOrDefault() {
        return defaultMinReplicas != null && defaultMinReplicas > 0 ? defaultMinReplicas : 1;
    }

    public int defaultMaxReplicasOrDefault() {
        return defaultMaxReplicas != null && defaultMaxReplicas > 0 ? defaultMaxReplicas : 10;
    }

    public int defaultTargetInFlightPerPodOrDefault() {
        return defaultTargetInFlightPerPod != null && defaultTargetInFlightPerPod > 0
                ? defaultTargetInFlightPerPod
                : 2;
    }

    public long concurrencyUpscaleCooldownMsOrDefault() {
        return concurrencyUpscaleCooldownMs != null && concurrencyUpscaleCooldownMs > 0
                ? concurrencyUpscaleCooldownMs
                : 30_000L;
    }

    public long concurrencyDownscaleCooldownMsOrDefault() {
        return concurrencyDownscaleCooldownMs != null && concurrencyDownscaleCooldownMs > 0
                ? concurrencyDownscaleCooldownMs
                : 60_000L;
    }

    public double concurrencyHighLoadThresholdOrDefault() {
        return concurrencyHighLoadThreshold != null && concurrencyHighLoadThreshold > 0
                ? concurrencyHighLoadThreshold
                : 0.85;
    }

    public double concurrencyLowLoadThresholdOrDefault() {
        return concurrencyLowLoadThreshold != null && concurrencyLowLoadThreshold >= 0
                ? concurrencyLowLoadThreshold
                : 0.35;
    }
}
