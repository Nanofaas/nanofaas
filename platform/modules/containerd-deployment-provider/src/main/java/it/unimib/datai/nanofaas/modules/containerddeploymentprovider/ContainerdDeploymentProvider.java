package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.LocalDeploymentSettings;
import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class ContainerdDeploymentProvider extends LocalManagedDeploymentProvider {
    public ContainerdDeploymentProvider(ContainerRuntimeAdapter adapter, ContainerdProperties properties,
                                        EndpointProbe endpointProbe, RoundRobinFunctionProxyFactory proxyFactory) {
        super("containerd", new LocalDeploymentSettings(properties.callbackUrl(),
                properties.readinessTimeout(), properties.readinessPollInterval()),
                adapter, endpointProbe, proxyFactory);
    }

    @Override
    protected String containerNamePrefix(String functionName) {
        return namePrefix(functionName);
    }

    static String namePrefix(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("function name is required for containerd deployment");
        }
        String slug = trimDashes(functionName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        if (slug.isEmpty()) slug = "fn";
        if (slug.length() > 44) slug = trimDashes(slug.substring(0, 44));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(functionName.getBytes(StandardCharsets.UTF_8));
            return "nanofaas-" + slug + "-" + HexFormat.of().formatHex(digest, 0, 5);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String trimDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') start++;
        while (end > start && value.charAt(end - 1) == '-') end--;
        return value.substring(start, end);
    }

    @Override
    protected String containerName(String functionName, int replicaIndex) {
        if (replicaIndex < 1) throw new IllegalArgumentException("replica index must be positive");
        return containerNamePrefix(functionName) + "-r" + replicaIndex;
    }
}
