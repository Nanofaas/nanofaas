package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.LocalDeploymentSettings;
import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;

public class ContainerLocalDeploymentProvider extends LocalManagedDeploymentProvider {
    private final String namespace;
    @Override
    protected String containerNamePrefix(String function) {
        String slug = super.containerNamePrefix(function).substring("nanofaas-".length());
        if (slug.length() > 44) slug = slug.substring(0, 44);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(function.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String prefix = "nanofaas-" + slug + "-" + java.util.HexFormat.of().formatHex(digest, 0, 8);
            return namespace == null ? prefix : namespace + "-" + prefix;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    protected String legacyContainerNamePrefix(String function) {
        String prefix = super.containerNamePrefix(function);
        return namespace == null ? prefix : namespace + "-" + prefix;
    }

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
