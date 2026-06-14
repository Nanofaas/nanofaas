package it.unimib.datai.nanofaas.controlplane.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.deployment")
public record DeploymentProperties(
        String defaultBackend
) {
    public DeploymentProperties() {
        this(null);
    }
}
