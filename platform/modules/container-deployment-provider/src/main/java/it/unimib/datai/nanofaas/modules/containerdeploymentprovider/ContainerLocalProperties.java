package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nanofaas.container-local")
public record ContainerLocalProperties(
        String runtimeAdapter,
        String bindHost,
        Duration readinessTimeout,
        Duration readinessPollInterval,
        String callbackUrl
) {
    public ContainerLocalProperties() {
        this("docker", "127.0.0.1", Duration.ofSeconds(20), Duration.ofMillis(250), null);
    }
}
