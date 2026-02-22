package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.k8s")
public record KubernetesProperties(
        String namespace,
        String callbackUrl
) {
}
