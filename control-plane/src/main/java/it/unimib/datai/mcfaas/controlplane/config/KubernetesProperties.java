package it.unimib.datai.mcfaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcfaas.k8s")
public record KubernetesProperties(
        String namespace,
        String callbackUrl,
        Integer apiTimeoutSeconds
) {
    public int apiTimeoutSecondsOrDefault() {
        return apiTimeoutSeconds != null && apiTimeoutSeconds > 0 ? apiTimeoutSeconds : 10;
    }
}
