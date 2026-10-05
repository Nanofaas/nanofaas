package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.LocalDeploymentSettings;
import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;

public class ContainerLocalDeploymentProvider extends LocalManagedDeploymentProvider {
    private final String namespace;
    @Override protected String containerNamePrefix(String function) { return namespace==null?super.containerNamePrefix(function):namespace+"-"+super.containerNamePrefix(function); }
    @Override protected String containerFunctionLabel(String function) { return namespace==null?function:namespace+"/"+function; }
    static final String BACKEND_ID = "container-local";

    public ContainerLocalDeploymentProvider(ContainerRuntimeAdapter adapter,
                                            ContainerLocalProperties properties,
                                            EndpointProbe endpointProbe,
                                            RoundRobinFunctionProxyFactory proxyFactory) {
        super(BACKEND_ID, new LocalDeploymentSettings(properties.callbackUrl(),
                properties.readinessTimeout(), properties.readinessPollInterval()),
                adapter, endpointProbe, proxyFactory);
        namespace=properties.namespace();
    }
}
