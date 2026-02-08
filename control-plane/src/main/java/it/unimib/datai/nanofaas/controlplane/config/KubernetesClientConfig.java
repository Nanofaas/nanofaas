package it.unimib.datai.nanofaas.controlplane.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.impl.KubernetesClientImpl;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.vertx.VertxHttpClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesClientConfig {
    @Bean
    public KubernetesClient kubernetesClient() {
        // Avoid reflective lookup in KubernetesClientBuilder which is fragile in native-image.
        // Also avoid parsing kubeconfig YAML at startup (requires extra reflection config in native image).
        Config config = Config.empty();
        Config.configFromSysPropsOrEnvVars(config);
        HttpClient httpClient = new VertxHttpClientFactory().newBuilder(config).build();
        return new KubernetesClientImpl(httpClient, config);
    }
}
