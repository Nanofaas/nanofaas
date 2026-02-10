package it.unimib.datai.nanofaas.controlplane.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.impl.KubernetesClientImpl;
import io.fabric8.kubernetes.client.vertx.VertxHttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class KubernetesClientConfig {

    private static final Logger log = LoggerFactory.getLogger(KubernetesClientConfig.class);
    private static final Path SA_TOKEN = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path SA_CA    = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

    @Bean
    public KubernetesClient kubernetesClient() {
        if (Files.exists(SA_TOKEN)) {
            return inClusterClient();
        }
        // Local / dev: KubernetesClientBuilder reads ~/.kube/config automatically
        return new KubernetesClientBuilder()
                .withHttpClientFactory(new VertxHttpClientFactory())
                .build();
    }

    private KubernetesClient inClusterClient() {
        try {
            String host = System.getenv("KUBERNETES_SERVICE_HOST");
            String port = System.getenv("KUBERNETES_SERVICE_PORT");
            String token = Files.readString(SA_TOKEN).trim();
            String caCert = SA_CA.toAbsolutePath().toString();
            String masterUrl = "https://" + host + ":" + port;

            log.info("In-cluster K8s config: masterUrl={}, caCert={}, tokenLen={}",
                    masterUrl, caCert, token.length());

            Config config = new ConfigBuilder()
                    .withMasterUrl(masterUrl)
                    .withOauthToken(token)
                    .withCaCertFile(caCert)
                    .withTrustCerts(true)
                    .withAutoOAuthToken(token)
                    .build();

            log.info("Config built: masterUrl={}, hasToken={}", config.getMasterUrl(), config.getOauthToken() != null);

            // Use KubernetesClientImpl directly to avoid KubernetesClientBuilder overriding config
            HttpClient httpClient = new VertxHttpClientFactory().newBuilder(config).build();
            return new KubernetesClientImpl(httpClient, config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read in-cluster ServiceAccount credentials", e);
        }
    }
}
