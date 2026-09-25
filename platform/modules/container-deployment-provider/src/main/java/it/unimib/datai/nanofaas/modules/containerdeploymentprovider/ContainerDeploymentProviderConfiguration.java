package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.HttpEndpointProbe;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.time.Duration;

@AutoConfiguration
@EnableConfigurationProperties({ContainerLocalProperties.class, ContainerProxyProperties.class})
@ImportRuntimeHints(DockerJavaRuntimeHints.class)
public class ContainerDeploymentProviderConfiguration {

    @Bean
    CliCommandExecutor cliCommandExecutor() {
        return new ProcessCliCommandExecutor();
    }

    @Bean
    ContainerRuntimeAdapter containerRuntimeAdapter(ContainerLocalProperties properties,
                                                    CliCommandExecutor executor, PortAllocator portAllocator) {
        if ("docker-java".equalsIgnoreCase(properties.runtimeAdapter())) {
            return new DockerJavaContainerRuntimeAdapter(
                    createDockerClient(), properties.networkName(), properties.cpuset(),
                    properties.bindHost(), portAllocator);
        }
        if (properties.networkName() != null) {
            throw new IllegalArgumentException("nanofaas.container-local.network-name requires the docker-java runtime adapter");
        }
        return new CliContainerRuntimeAdapter(
                properties.runtimeAdapter(), executor, properties.cpuset(), properties.bindHost(), portAllocator);
    }

    @Bean
    @ConditionalOnProperty(
            name = "nanofaas.deployment.default-backend",
            havingValue = "container-local",
            matchIfMissing = true
    )
    ImageValidator dockerImageValidator(ContainerRuntimeAdapter adapter) {
        return new DockerImageValidator(adapter);
    }

    static DockerClient createDockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
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
    RoundRobinFunctionProxyFactory managedFunctionProxyFactory(ContainerLocalProperties properties,
                                                            ContainerProxyProperties proxyProperties) {
        return new RoundRobinFunctionProxyFactory(properties.bindHost(), proxyProperties.settings());
    }

    @Bean
    ContainerLocalDeploymentProvider containerLocalDeploymentProvider(ContainerRuntimeAdapter adapter,
                                                                     ContainerLocalProperties properties,
                                                                     EndpointProbe endpointProbe,
                                                                     RoundRobinFunctionProxyFactory proxyFactory) {
        return new ContainerLocalDeploymentProvider(adapter, properties, endpointProbe, proxyFactory);
    }
}
