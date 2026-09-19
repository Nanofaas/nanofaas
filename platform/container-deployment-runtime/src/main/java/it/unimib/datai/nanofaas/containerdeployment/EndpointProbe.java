package it.unimib.datai.nanofaas.containerdeployment;

import java.time.Duration;

public interface EndpointProbe {
    void awaitReady(String baseUrl, Duration timeout, Duration pollInterval);

    boolean isReady(String baseUrl);
}
