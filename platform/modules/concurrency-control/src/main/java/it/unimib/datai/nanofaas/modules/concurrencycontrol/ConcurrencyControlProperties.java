package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "nanofaas.concurrency-control")
public record ConcurrencyControlProperties(
        Long pollIntervalMs,
        Integer defaultTargetInFlightPerPod
) {
    @ConstructorBinding
    public ConcurrencyControlProperties {
    }

    public long pollIntervalMsOrDefault() {
        return pollIntervalMs != null && pollIntervalMs > 0 ? pollIntervalMs : 5000;
    }

    public int defaultTargetInFlightPerPodOrDefault() {
        return defaultTargetInFlightPerPod != null && defaultTargetInFlightPerPod > 0
                ? defaultTargetInFlightPerPod
                : 2;
    }
}
