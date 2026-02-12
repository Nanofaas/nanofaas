package it.unimib.datai.nanofaas.cli.commands.platform;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import it.unimib.datai.nanofaas.cli.testsupport.CliTestSupport;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class PlatformCommandTest {

    KubernetesClient client;

    @Test
    void installRunsHelmWithNodePortDefaultsAndPrintsEndpoint() {
        client.nodes().resource(new NodeBuilder()
                .withNewMetadata().withName("node-1").endMetadata()
                .withNewStatus()
                    .addNewAddress().withType("InternalIP").withAddress("192.168.64.10").endAddress()
                .endStatus()
                .build()).create();

        client.services().inNamespace("nanofaas").resource(new ServiceBuilder()
                .withNewMetadata().withName("control-plane").withNamespace("nanofaas").endMetadata()
                .withNewSpec()
                    .withType("NodePort")
                    .addNewPort().withName("http").withPort(8080).withNodePort(30080).endPort()
                    .addNewPort().withName("actuator").withPort(8081).withNodePort(30081).endPort()
                .endSpec()
                .build()).create();

        List<List<String>> helmCalls = new ArrayList<>();
        PlatformCommand platform = new PlatformCommand(() -> client, cmd -> helmCalls.add(new ArrayList<>(cmd)));
        CommandLine cli = new CommandLine(platform);

        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(cli, "install");
        assertThat(result.exitCode()).isEqualTo(0);

        assertThat(helmCalls).hasSize(1);
        assertThat(helmCalls.get(0)).containsSequence(
                "helm", "upgrade", "--install", "nanofaas", "helm/nanofaas",
                "--namespace", "nanofaas", "--create-namespace", "--wait",
                "--set", "namespace.create=false",
                "--set", "controlPlane.service.type=NodePort",
                "--set", "controlPlane.service.nodePorts.http=30080",
                "--set", "controlPlane.service.nodePorts.actuator=30081"
        );
        assertThat(result.stdout()).contains("http://192.168.64.10:30080");
    }

    @Test
    void installSupportsImageRepositoryPullPolicyAndDemosOverride() {
        client.nodes().resource(new NodeBuilder()
                .withNewMetadata().withName("node-1").endMetadata()
                .withNewStatus()
                    .addNewAddress().withType("InternalIP").withAddress("192.168.64.12").endAddress()
                .endStatus()
                .build()).create();

        client.services().inNamespace("nanofaas").resource(new ServiceBuilder()
                .withNewMetadata().withName("control-plane").withNamespace("nanofaas").endMetadata()
                .withNewSpec()
                    .withType("NodePort")
                    .addNewPort().withName("http").withPort(8080).withNodePort(30080).endPort()
                    .addNewPort().withName("actuator").withPort(8081).withNodePort(30081).endPort()
                .endSpec()
                .build()).create();

        List<List<String>> helmCalls = new ArrayList<>();
        PlatformCommand platform = new PlatformCommand(() -> client, cmd -> helmCalls.add(new ArrayList<>(cmd)));
        CommandLine cli = new CommandLine(platform);

        int exit = cli.execute(
                "install",
                "--control-plane-repository", "nanofaas/control-plane",
                "--control-plane-tag", "e2e",
                "--control-plane-pull-policy", "Never",
                "--demos-enabled=false"
        );
        assertThat(exit).isEqualTo(0);

        assertThat(helmCalls).hasSize(1);
        assertThat(helmCalls.get(0)).containsSequence(
                "--set", "controlPlane.image.repository=nanofaas/control-plane",
                "--set", "controlPlane.image.tag=e2e",
                "--set", "controlPlane.image.pullPolicy=Never",
                "--set", "demos.enabled=false"
        );
    }

    @Test
    void statusPrintsReadinessAndResolvedEndpoint() {
        client.nodes().resource(new NodeBuilder()
                .withNewMetadata().withName("node-1").endMetadata()
                .withNewStatus()
                    .addNewAddress().withType("InternalIP").withAddress("192.168.64.11").endAddress()
                .endStatus()
                .build()).create();

        client.services().inNamespace("nanofaas").resource(new ServiceBuilder()
                .withNewMetadata().withName("control-plane").withNamespace("nanofaas").endMetadata()
                .withNewSpec()
                    .withType("NodePort")
                    .addNewPort().withName("http").withPort(8080).withNodePort(30080).endPort()
                .endSpec()
                .build()).create();

        client.apps().deployments().inNamespace("nanofaas").resource(new DeploymentBuilder()
                .withNewMetadata().withName("nanofaas-control-plane").withNamespace("nanofaas").endMetadata()
                .withNewStatus()
                    .withReplicas(1)
                    .withReadyReplicas(1)
                .endStatus()
                .build()).create();

        PlatformCommand platform = new PlatformCommand(() -> client, cmd -> {});
        CommandLine cli = new CommandLine(platform);

        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(cli, "status");
        assertThat(result.exitCode()).isEqualTo(0);

        String output = result.stdout();
        assertThat(output).contains("deployment\tnanofaas-control-plane\t1/1");
        assertThat(output).contains("service\tcontrol-plane\tNodePort");
        assertThat(output).contains("endpoint\thttp://192.168.64.11:30080");
    }

    @Test
    void uninstallRunsHelmUninstallWithReleaseAndNamespace() {
        List<List<String>> helmCalls = new ArrayList<>();
        PlatformCommand platform = new PlatformCommand(() -> client, cmd -> helmCalls.add(new ArrayList<>(cmd)));
        CommandLine cli = new CommandLine(platform);

        int exit = cli.execute("uninstall", "--release", "my-nanofaas", "--namespace", "dev");
        assertThat(exit).isEqualTo(0);

        assertThat(helmCalls).hasSize(1);
        assertThat(helmCalls.get(0)).containsExactly(
                "helm", "uninstall", "my-nanofaas", "--namespace", "dev"
        );
    }
}
