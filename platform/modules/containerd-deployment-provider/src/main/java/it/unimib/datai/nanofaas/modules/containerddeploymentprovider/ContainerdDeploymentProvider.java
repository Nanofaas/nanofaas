package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.LocalDeploymentSettings;
import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxyFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class ContainerdDeploymentProvider extends LocalManagedDeploymentProvider {
    public ContainerdDeploymentProvider(ContainerRuntimeAdapter adapter, ContainerdProperties properties,
                                        EndpointProbe endpointProbe, ManagedFunctionProxyFactory proxyFactory) {
        super("containerd", new LocalDeploymentSettings(properties.callbackUrl(),
                properties.readinessTimeout(), properties.readinessPollInterval()),
                adapter, endpointProbe, proxyFactory);
    }

    @Override
    protected String containerNamePrefix(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("function name is required for containerd deployment");
        }
        String slug = functionName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "fn";
        if (slug.length() > 44) slug = slug.substring(0, 44).replaceAll("-+$", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(functionName.getBytes(StandardCharsets.UTF_8));
            return "nanofaas-" + slug + "-" + HexFormat.of().formatHex(digest, 0, 5);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    protected String containerName(String functionName, int replicaIndex) {
        if (replicaIndex < 1) throw new IllegalArgumentException("replica index must be positive");
        return containerNamePrefix(functionName) + "-r" + replicaIndex;
    }
}
