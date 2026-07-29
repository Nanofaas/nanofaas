package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "nanofaas.container-local")
public record ContainerLocalProperties(
        String runtimeAdapter,
        String bindHost,
        Duration readinessTimeout,
        Duration readinessPollInterval,
        String callbackUrl,
        String networkName
) {
    public ContainerLocalProperties(String runtimeAdapter,
                                    String bindHost,
                                    Duration readinessTimeout,
                                    Duration readinessPollInterval,
                                    String callbackUrl) {
        this(runtimeAdapter, bindHost, readinessTimeout, readinessPollInterval, callbackUrl, null);
    }

    @ConstructorBinding
    public ContainerLocalProperties {
        if (runtimeAdapter == null || runtimeAdapter.isBlank()) {
            runtimeAdapter = "docker";
        }
        if (bindHost == null || bindHost.isBlank()) {
            bindHost = "127.0.0.1";
        }
        if (readinessTimeout == null) {
            readinessTimeout = Duration.ofSeconds(20);
        }
        if (readinessPollInterval == null) {
            readinessPollInterval = Duration.ofMillis(250);
        }
        if (networkName != null && networkName.isBlank()) {
            networkName = null;
        }
    }
}
