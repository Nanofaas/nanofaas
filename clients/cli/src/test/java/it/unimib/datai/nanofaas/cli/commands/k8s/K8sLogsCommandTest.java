package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class K8sLogsCommandTest {

    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void logsUsesFunctionLabelSelectorAndFunctionContainer() {
        // Arrange: pod with label function=echo and container name "function"
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName("fn-echo-abc")
                    .withNamespace("ns")
                    .addToLabels("function", "echo")
                .endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("function").withImage("x").endContainer()
                .endSpec()
                .build();
        client.pods().inNamespace("ns").resource(pod).create();

        // Fabric8 mock server can stub logs via expectation
        server.expect().get().withPath("/api/v1/namespaces/ns/pods/fn-echo-abc/log?pretty=false&container=function")
                .andReturn(200, "hello\n").always();

        // Act
        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("logs", "echo");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        // Assert
        assertThat(out.toString()).contains("hello");
    }

    @Test
    void logsWithNoPodsExitsNonZero() {
        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute("logs", "missing");
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void logsSelectsReadyPodOverNonReady() {
        // Create non-ready pod (older)
        Pod notReady = new PodBuilder()
                .withNewMetadata()
                    .withName("fn-echo-old")
                    .withNamespace("ns")
                    .addToLabels("function", "echo")
                    .withCreationTimestamp("2024-01-01T00:00:00Z")
                .endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("function").withImage("x").endContainer()
                .endSpec()
                .withNewStatus()
                    .addNewCondition().withType("Ready").withStatus("False").endCondition()
                .endStatus()
                .build();
        client.pods().inNamespace("ns").resource(notReady).create();

        // Create ready pod (newer)
        Pod ready = new PodBuilder()
                .withNewMetadata()
                    .withName("fn-echo-new")
                    .withNamespace("ns")
                    .addToLabels("function", "echo")
                    .withCreationTimestamp("2024-01-02T00:00:00Z")
                .endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("function").withImage("x").endContainer()
                .endSpec()
                .withNewStatus()
                    .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .endStatus()
                .build();
        client.pods().inNamespace("ns").resource(ready).create();

        // Stub logs for the ready pod
        server.expect().get()
                .withPath("/api/v1/namespaces/ns/pods/fn-echo-new/log?pretty=false&container=function")
                .andReturn(200, "from-ready-pod\n").always();

        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("logs", "echo");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        assertThat(out.toString()).contains("from-ready-pod");
    }
}
