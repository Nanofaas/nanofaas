package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.util.List;

public interface ManagedFunctionProxy extends AutoCloseable {
    String endpointUrl();

    void updateBackends(List<String> backendBaseUrls);

    @Override
    void close();
}
