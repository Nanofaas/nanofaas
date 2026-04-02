package it.unimib.datai.nanofaas.controlplane.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KubernetesClientConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void kubernetesClient_withoutServiceAccountToken_buildsClient() {
        Path missingTokenPath = tempDir.resolve("missing-token");
        KubernetesClientConfig config = new KubernetesClientConfig(
                missingTokenPath,
                tempDir.resolve("ca.crt"),
                key -> null
        );

        try (KubernetesClient client = config.kubernetesClient()) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void kubernetesClient_withInClusterCredentials_buildsConfiguredClient() throws IOException {
        Path tokenPath = tempDir.resolve("token");
        Path caPath = tempDir.resolve("ca.crt");
        Files.writeString(tokenPath, "token-123\n");
        Files.writeString(caPath, "-----BEGIN CERTIFICATE-----\nmock\n-----END CERTIFICATE-----\n");

        Map<String, String> env = Map.of(
                "KUBERNETES_SERVICE_HOST", "10.1.2.3",
                "KUBERNETES_SERVICE_PORT", "6443"
        );

        KubernetesClientConfig config = new KubernetesClientConfig(tokenPath, caPath, env::get);

        try (KubernetesClient client = config.kubernetesClient()) {
            assertThat(client).isNotNull();
            assertThat(client.getMasterUrl().toString()).startsWith("https://10.1.2.3:6443");
            assertThat(client.getConfiguration().getOauthToken()).isEqualTo("token-123");
            assertThat(client.getConfiguration().getCaCertFile()).isEqualTo(caPath.toAbsolutePath().toString());
        }
    }

    @Test
    void kubernetesClient_inClusterWithoutHostOrPort_throwsIllegalStateException() throws IOException {
        Path tokenPath = tempDir.resolve("token");
        Path caPath = tempDir.resolve("ca.crt");
        Files.writeString(tokenPath, "token-123");
        Files.writeString(caPath, "mock");

        KubernetesClientConfig config = new KubernetesClientConfig(
                tokenPath,
                caPath,
                key -> "KUBERNETES_SERVICE_HOST".equals(key) ? "10.1.2.3" : null
        );

        assertThatThrownBy(config::kubernetesClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing Kubernetes service host/port");
    }

    @Test
    void kubernetesClient_inClusterWithBlankToken_throwsIllegalStateException() throws IOException {
        Path tokenPath = tempDir.resolve("token");
        Path caPath = tempDir.resolve("ca.crt");
        Files.writeString(tokenPath, "   \n\t");
        Files.writeString(caPath, "mock");

        KubernetesClientConfig config = new KubernetesClientConfig(
                tokenPath,
                caPath,
                key -> switch (key) {
                    case "KUBERNETES_SERVICE_HOST" -> "10.1.2.3";
                    case "KUBERNETES_SERVICE_PORT" -> "6443";
                    default -> null;
                }
        );

        assertThatThrownBy(config::kubernetesClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ServiceAccount token is empty");
    }

    @Test
    void kubernetesClient_inClusterWhenTokenUnreadable_throwsIllegalStateException() throws IOException {
        Path tokenPath = tempDir.resolve("token-dir");
        Path caPath = tempDir.resolve("ca.crt");
        Files.createDirectory(tokenPath);
        Files.writeString(caPath, "mock");

        KubernetesClientConfig config = new KubernetesClientConfig(
                tokenPath,
                caPath,
                key -> switch (key) {
                    case "KUBERNETES_SERVICE_HOST" -> "10.1.2.3";
                    case "KUBERNETES_SERVICE_PORT" -> "6443";
                    default -> null;
                }
        );

        assertThatThrownBy(config::kubernetesClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read in-cluster ServiceAccount credentials");
    }
}
