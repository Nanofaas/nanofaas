package it.unimib.datai.nanofaas.controlplane.scaling;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.scaling")
public record ScalingProperties(
        Long pollIntervalMs,
        Integer defaultMinReplicas,
        Integer defaultMaxReplicas
) {
    public long pollIntervalMsOrDefault() {
        return pollIntervalMs != null && pollIntervalMs > 0 ? pollIntervalMs : 5000;
    }

    public int defaultMinReplicasOrDefault() {
        return defaultMinReplicas != null && defaultMinReplicas > 0 ? defaultMinReplicas : 1;
    }

    public int defaultMaxReplicasOrDefault() {
        return defaultMaxReplicas != null && defaultMaxReplicas > 0 ? defaultMaxReplicas : 10;
    }
}
