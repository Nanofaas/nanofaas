package it.unimib.datai.nanofaas.cli.commands.platform;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "status", description = "Show nanofaas control-plane status and resolved endpoint.")
public class PlatformStatusCommand implements Runnable {

    @ParentCommand
    PlatformCommand parent;

    @Option(names = {"-n", "--namespace"}, description = "Target namespace.")
    String namespace;

    @Override
    public void run() {
        String ns = parent.resolveNamespace(namespace);
        KubernetesClient client = parent.client();

        Deployment deployment = client.apps().deployments().inNamespace(ns).withName("nanofaas-control-plane").get();
        if (deployment == null) {
            throw new IllegalStateException("nanofaas control-plane deployment not found in namespace " + ns);
        }

        Service service = client.services().inNamespace(ns).withName("control-plane").get();
        if (service == null) {
            throw new IllegalStateException("nanofaas control-plane service not found in namespace " + ns);
        }

        int desired = (deployment.getStatus() == null || deployment.getStatus().getReplicas() == null)
                ? 0
                : deployment.getStatus().getReplicas();
        int ready = (deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null)
                ? 0
                : deployment.getStatus().getReadyReplicas();

        String svcType = (service.getSpec() == null || service.getSpec().getType() == null || service.getSpec().getType().isBlank())
                ? "ClusterIP"
                : service.getSpec().getType();

        String endpoint = parent.resolveEndpoint(client, ns, 8080);

        System.out.printf("deployment\t%s\t%d/%d%n", "nanofaas-control-plane", ready, desired);
        System.out.printf("service\t%s\t%s%n", "control-plane", svcType);
        System.out.printf("endpoint\t%s%n", endpoint);
    }
}
