package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class K8sPodsCommandTest {

    KubernetesClient client;

    @Test
    void podsWithFunctionLabelAreListed() {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName("fn-echo-abc")
                    .withNamespace("ns")
                    .addToLabels("function", "echo")
                .endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("function").withImage("x").endContainer()
                .endSpec()
                .withNewStatus().withPhase("Running").endStatus()
                .build();
        client.pods().inNamespace("ns").resource(pod).create();

        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("pods", "echo");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        String output = out.toString();
        assertThat(output).contains("fn-echo-abc");
        assertThat(output).contains("Running");
    }

    @Test
    void podWithNullStatusShowsEmptyPhase() {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName("fn-echo-nostatus")
                    .withNamespace("ns")
                    .addToLabels("function", "echo2")
                .endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("function").withImage("x").endContainer()
                .endSpec()
                .build();
        client.pods().inNamespace("ns").resource(pod).create();

        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("pods", "echo2");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        String output = out.toString();
        assertThat(output).contains("fn-echo-nostatus");
    }

    @Test
    void noMatchingPodsProducesEmptyOutput() {
        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("pods", "nonexistent");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        assertThat(out.toString().trim()).isEmpty();
    }
}
