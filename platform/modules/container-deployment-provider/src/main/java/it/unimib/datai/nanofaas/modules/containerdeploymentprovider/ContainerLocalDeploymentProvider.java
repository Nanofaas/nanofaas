package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.LocalDeploymentSettings;
import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxyFactory;

public class ContainerLocalDeploymentProvider extends LocalManagedDeploymentProvider {
    static final String BACKEND_ID = "container-local";

    public ContainerLocalDeploymentProvider(ContainerRuntimeAdapter adapter,
                                            ContainerLocalProperties properties,
                                            EndpointProbe endpointProbe,
                                            ManagedFunctionProxyFactory proxyFactory) {
        super(BACKEND_ID, new LocalDeploymentSettings(properties.callbackUrl(),
                properties.readinessTimeout(), properties.readinessPollInterval()),
                adapter, endpointProbe, proxyFactory);
    }
}
