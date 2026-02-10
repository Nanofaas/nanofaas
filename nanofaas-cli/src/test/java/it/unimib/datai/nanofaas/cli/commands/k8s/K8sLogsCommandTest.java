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
}
