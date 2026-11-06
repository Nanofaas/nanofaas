package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.spi.ContainerdClient;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.HttpEndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.ManagedReplicaPoolFactory;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ContainerdProperties.class)
public class ContainerdDeploymentProviderConfiguration {

    @Bean
    it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource containerdImageInventorySource(
            ContainerdClient client) {
        return new ContainerdImageInventorySource(client);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ContainerdClient.class)
    ContainerdClient containerdClient(ContainerdProperties properties) {
        return properties.newClient();
    }

    @Bean
    ContainerRuntimeAdapter containerdRuntimeAdapter(ContainerdClient client, ContainerdProperties properties) {
        return new ContainerdRuntimeAdapter(client, properties);
    }

    @Bean
    EndpointProbe containerdEndpointProbe() {
        return new HttpEndpointProbe();
    }

    @Bean
    ManagedReplicaPoolFactory containerdManagedFunctionProxyFactory() {
        return new ManagedReplicaPoolFactory();
    }

    @Bean
    ContainerdDeploymentProvider containerdDeploymentProvider(ContainerRuntimeAdapter adapter,
                                                               ContainerdProperties properties,
                                                               EndpointProbe endpointProbe,
                                                               ManagedReplicaPoolFactory proxyFactory) {
        return new ContainerdDeploymentProvider(adapter, properties, endpointProbe, proxyFactory);
    }

    @Bean
    @ConditionalOnExpression("'${nanofaas.deployment.default-backend:}'.trim().isEmpty()"
            + " || '${nanofaas.deployment.default-backend:}'.equalsIgnoreCase('containerd')")
    ImageValidator containerdImageValidator(ContainerRuntimeAdapter adapter) {
        return new ContainerdImageValidator(adapter);
    }
}
