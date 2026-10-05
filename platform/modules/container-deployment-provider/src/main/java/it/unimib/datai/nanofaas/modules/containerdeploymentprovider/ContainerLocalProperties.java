package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * @param cpuset the host CPUs every managed function container is pinned to, e.g. {@code "0-3"}.
 *               A per-function CPU limit caps each container separately, which on a machine with
 *               spare cores means the functions never actually compete — measured: two functions
 *               with four CPUs each on an eleven-core host shared a control plane and barely
 *               affected one another. Pinning them to the same cores is what makes the platform's
 *               capacity a quantity they have to divide, which is the premise the concurrency
 *               budget is built on.
 */
@ConfigurationProperties(prefix = "nanofaas.container-local")
public record ContainerLocalProperties(
        String runtimeAdapter,
        String bindHost,
        Duration readinessTimeout,
        Duration readinessPollInterval,
        String callbackUrl,
        String networkName,
        String cpuset,
        String namespace
) {
    public ContainerLocalProperties(String adapter,String host,Duration timeout,Duration poll,String callback,String network,String cpuset) {
        this(adapter,host,timeout,poll,callback,network,cpuset,null);
    }

    public ContainerLocalProperties(String runtimeAdapter,
                                    String bindHost,
                                    Duration readinessTimeout,
                                    Duration readinessPollInterval,
                                    String callbackUrl) {
        this(runtimeAdapter, bindHost, readinessTimeout, readinessPollInterval, callbackUrl,
                null, null);
    }

    public ContainerLocalProperties(String runtimeAdapter,
                                    String bindHost,
                                    Duration readinessTimeout,
                                    Duration readinessPollInterval,
                                    String callbackUrl,
                                    String networkName) {
        this(runtimeAdapter, bindHost, readinessTimeout, readinessPollInterval, callbackUrl,
                networkName, null);
    }

    @ConstructorBinding
    public ContainerLocalProperties {
        if(namespace!=null && !namespace.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid local container namespace");
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
        if (cpuset != null && cpuset.isBlank()) {
            cpuset = null;
        }
        if (networkName != null && networkName.isBlank()) {
            networkName = null;
        }
    }
}
