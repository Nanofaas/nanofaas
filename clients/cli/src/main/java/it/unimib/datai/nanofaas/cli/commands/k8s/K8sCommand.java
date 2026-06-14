package it.unimib.datai.nanofaas.cli.commands.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

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

    @ParentCommand
    private RootCommand root;

    private final Supplier<KubernetesClient> clientSupplier;
    private final Supplier<String> namespaceSupplier;

    public K8sCommand() {
        this(() -> new KubernetesClientBuilder().build(), null);
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
        if (namespaceSupplier != null) {
            return namespaceSupplier.get();
        }
        if (root != null) {
            String ns = root.resolvedContext().namespace();
            if (ns != null && !ns.isBlank()) {
                return ns;
            }
        }
        return "default";
    }
}
