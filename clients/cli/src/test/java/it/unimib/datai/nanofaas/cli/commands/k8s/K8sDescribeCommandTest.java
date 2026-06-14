package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class K8sDescribeCommandTest {

    KubernetesClient client;

    @Test
    void describeAllMissing() {
        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("describe", "nonexistent");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        String output = out.toString();
        assertThat(output).contains("deployment\tfn-nonexistent\tmissing");
        assertThat(output).contains("service\tfn-nonexistent\tmissing");
        assertThat(output).contains("hpa\tfn-nonexistent\tmissing");
    }

    @Test
    void describeAllPresent() {
        client.apps().deployments().inNamespace("ns").resource(
                new DeploymentBuilder()
                        .withNewMetadata().withName("fn-all").withNamespace("ns").endMetadata()
                        .withNewSpec()
                            .withNewSelector().addToMatchLabels("function", "all").endSelector()
                            .withNewTemplate()
                                .withNewMetadata().addToLabels("function", "all").endMetadata()
                                .withNewSpec()
                                    .addNewContainer().withName("function").withImage("x").endContainer()
                                .endSpec()
                            .endTemplate()
                        .endSpec()
                        .build()
        ).create();

        client.services().inNamespace("ns").resource(
                new ServiceBuilder()
                        .withNewMetadata().withName("fn-all").withNamespace("ns").endMetadata()
                        .withNewSpec()
                            .addNewPort().withPort(8080).endPort()
                        .endSpec()
                        .build()
        ).create();

        client.autoscaling().v2().horizontalPodAutoscalers().inNamespace("ns").resource(
                new io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder()
                        .withNewMetadata().withName("fn-all").withNamespace("ns").endMetadata()
                        .withNewSpec()
                            .withNewScaleTargetRef().withKind("Deployment").withName("fn-all").endScaleTargetRef()
                            .withMinReplicas(1).withMaxReplicas(3)
                        .endSpec()
                        .build()
        ).create();

        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("describe", "all");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        String output = out.toString();
        assertThat(output).contains("deployment\tfn-all\tpresent");
        assertThat(output).contains("service\tfn-all\tpresent");
        assertThat(output).contains("hpa\tfn-all\tpresent");
    }

    @Test
    void describeShowsPresentAndMissing() {
        // Create deployment and service, but no HPA
        client.apps().deployments().inNamespace("ns").resource(
                new DeploymentBuilder()
                        .withNewMetadata().withName("fn-echo").withNamespace("ns").endMetadata()
                        .withNewSpec()
                            .withNewSelector().addToMatchLabels("function", "echo").endSelector()
                            .withNewTemplate()
                                .withNewMetadata().addToLabels("function", "echo").endMetadata()
                                .withNewSpec()
                                    .addNewContainer().withName("function").withImage("x").endContainer()
                                .endSpec()
                            .endTemplate()
                        .endSpec()
                        .build()
        ).create();

        client.services().inNamespace("ns").resource(
                new ServiceBuilder()
                        .withNewMetadata().withName("fn-echo").withNamespace("ns").endMetadata()
                        .withNewSpec()
                            .addNewPort().withPort(8080).endPort()
                        .endSpec()
                        .build()
        ).create();

        K8sCommand root = new K8sCommand(() -> client, () -> "ns");
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("describe", "echo");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        String output = out.toString();
        assertThat(output).contains("deployment\tfn-echo\tpresent");
        assertThat(output).contains("service\tfn-echo\tpresent");
        assertThat(output).contains("hpa\tfn-echo\tmissing");
    }
}
