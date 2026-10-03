package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@ComponentScan(basePackageClasses = KubernetesDeploymentProviderConfiguration.class)
@EnableConfigurationProperties(KubernetesProperties.class)
public class KubernetesDeploymentProviderConfiguration {
    @org.springframework.context.annotation.Bean
    it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource kubernetesImageInventorySource(
            io.fabric8.kubernetes.client.KubernetesClient client) {
        return new KubernetesImageInventorySource(client);
    }
}
