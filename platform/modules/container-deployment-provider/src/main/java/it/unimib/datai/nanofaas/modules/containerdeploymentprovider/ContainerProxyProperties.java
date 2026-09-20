package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ProxySettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "nanofaas.container-local.proxy")
public record ContainerProxyProperties(int maxRequestBytes, int maxResponseBytes,
        long maxBufferedBytes, Duration inboundReadTimeout, Duration responseWriteTimeout) {
    public ContainerProxyProperties {
        ProxySettings settings = new ProxySettings(maxRequestBytes, maxResponseBytes,
                maxBufferedBytes, inboundReadTimeout, responseWriteTimeout);
        maxRequestBytes = settings.maxRequestBytes();
        maxResponseBytes = settings.maxResponseBytes();
        maxBufferedBytes = settings.maxBufferedBytes();
        inboundReadTimeout = settings.inboundReadTimeout();
        responseWriteTimeout = settings.responseWriteTimeout();
    }

    public static ContainerProxyProperties defaults() {
        return new ContainerProxyProperties(0, 0, 0, null, null);
    }

    public ProxySettings settings() {
        return new ProxySettings(maxRequestBytes, maxResponseBytes, maxBufferedBytes,
                inboundReadTimeout, responseWriteTimeout);
    }
}
