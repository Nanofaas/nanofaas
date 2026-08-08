package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ContainerLocalProperties.class)
@ImportRuntimeHints(DockerJavaRuntimeHints.class)
public class ContainerDeploymentProviderConfiguration {

    @Bean
    CliCommandExecutor cliCommandExecutor() {
        return new ProcessCliCommandExecutor();
    }

    @Bean
    ContainerRuntimeAdapter containerRuntimeAdapter(ContainerLocalProperties properties,
                                                    CliCommandExecutor executor) {
        if ("docker-java".equalsIgnoreCase(properties.runtimeAdapter())) {
            return new DockerJavaContainerRuntimeAdapter(createDockerClient(), properties.networkName());
        }
        return new CliContainerRuntimeAdapter(properties.runtimeAdapter(), executor);
    }

    @Bean
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
