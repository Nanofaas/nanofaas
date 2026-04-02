package it.unimib.datai.nanofaas.controlplane.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
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
import java.util.function.Function;

@Configuration
public class KubernetesClientConfig {

    private static final Logger log = LoggerFactory.getLogger(KubernetesClientConfig.class);
    private static final Path SA_TOKEN = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path SA_CA    = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");
    private final Path saTokenPath;
    private final Path saCaPath;
    private final Function<String, String> envProvider;

    public KubernetesClientConfig() {
        this(SA_TOKEN, SA_CA, System::getenv);
    }

    KubernetesClientConfig(Path saTokenPath, Path saCaPath, Function<String, String> envProvider) {
        this.saTokenPath = saTokenPath;
        this.saCaPath = saCaPath;
        this.envProvider = envProvider;
    }

    @Bean
    public KubernetesClient kubernetesClient() {
        if (Files.exists(saTokenPath)) {
            return inClusterClient();
        }
        // Local / dev: mirror KubernetesClientBuilder defaults without reflective class loading.
        Config config = Config.autoConfigure(null);
        return createClient(config);
    }

    private KubernetesClient inClusterClient() {
        try {
            String host = envProvider.apply("KUBERNETES_SERVICE_HOST");
            String port = envProvider.apply("KUBERNETES_SERVICE_PORT");
            if (host == null || host.isBlank() || port == null || port.isBlank()) {
                throw new IllegalStateException("Missing Kubernetes service host/port environment variables");
            }

            String token = Files.readString(saTokenPath).trim();
            if (token.isBlank()) {
                throw new IllegalStateException("ServiceAccount token is empty");
            }

            String caCert = saCaPath.toAbsolutePath().toString();
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

            return createClient(config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read in-cluster ServiceAccount credentials", e);
        }
    }

    private KubernetesClient createClient(Config config) {
        HttpClient httpClient = new VertxHttpClientFactory().newBuilder(config).build();
        return new KubernetesClientImpl(httpClient, config);
    }
}
