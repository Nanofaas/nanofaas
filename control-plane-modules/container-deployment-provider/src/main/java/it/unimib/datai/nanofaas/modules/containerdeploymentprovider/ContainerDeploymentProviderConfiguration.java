package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ContainerLocalProperties.class)
public class ContainerDeploymentProviderConfiguration {

    @Bean
    CliCommandExecutor cliCommandExecutor() {
        return new ProcessCliCommandExecutor();
    }

    @Bean
    ContainerRuntimeAdapter containerRuntimeAdapter(ContainerLocalProperties properties,
                                                    CliCommandExecutor executor) {
        return new CliContainerRuntimeAdapter(properties.runtimeAdapter(), executor);
    }

    @Bean
    EndpointProbe endpointProbe() {
        return new HttpEndpointProbe();
    }

    @Bean
    PortAllocator portAllocator(ContainerLocalProperties properties) {
        return new EphemeralPortAllocator(properties.bindHost());
    }

    @Bean
    ManagedFunctionProxyFactory managedFunctionProxyFactory(ContainerLocalProperties properties) {
        return new RoundRobinFunctionProxyFactory(properties.bindHost());
    }

    @Bean
    ContainerLocalDeploymentProvider containerLocalDeploymentProvider(ContainerRuntimeAdapter adapter,
                                                                     ContainerLocalProperties properties,
                                                                     EndpointProbe endpointProbe,
                                                                     PortAllocator portAllocator,
                                                                     ManagedFunctionProxyFactory proxyFactory) {
        return new ContainerLocalDeploymentProvider(adapter, properties, endpointProbe, portAllocator, proxyFactory);
    }
}
