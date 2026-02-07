package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.k8s")
public record KubernetesProperties(
        String namespace,
        String callbackUrl,
        Integer apiTimeoutSeconds,
        Integer apiThreads
) {
    public int apiTimeoutSecondsOrDefault() {
        return apiTimeoutSeconds != null && apiTimeoutSeconds > 0 ? apiTimeoutSeconds : 10;
    }

    public int apiThreadsOrDefault() {
        return apiThreads != null && apiThreads > 0 ? apiThreads : 16;
    }
}
