package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import picocli.CommandLine.Command;

import java.util.function.Supplier;

@Command(
        name = "k8s",
        description = "Kubernetes helper commands.",
        subcommands = {
                K8sPodsCommand.class,
                K8sDescribeCommand.class,
                K8sLogsCommand.class
        }
)
public class K8sCommand {

    private final Supplier<KubernetesClient> clientSupplier;
    private final Supplier<String> namespaceSupplier;

    public K8sCommand() {
        this(() -> new KubernetesClientBuilder().build(), () -> "default");
    }

    // For tests.
    public K8sCommand(Supplier<KubernetesClient> clientSupplier, Supplier<String> namespaceSupplier) {
        this.clientSupplier = clientSupplier;
        this.namespaceSupplier = namespaceSupplier;
    }

    KubernetesClient client() {
        return clientSupplier.get();
    }

    String namespace() {
        return namespaceSupplier.get();
    }
}
