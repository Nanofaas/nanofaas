package it.unimib.datai.nanofaas.cli.commands.platform;

import io.fabric8.kubernetes.client.KubernetesClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "install",
        mixinStandardHelpOptions = true,
        description = "Install or upgrade nanofaas via Helm (NodePort defaults for k3s)."
)
public class PlatformInstallCommand implements Runnable {

    @ParentCommand
    PlatformCommand parent;

    @Option(names = {"--release"}, defaultValue = "nanofaas", description = "Helm release name.")
    String release;

    @Option(names = {"--chart"}, defaultValue = "helm/nanofaas", description = "Chart reference/path.")
    String chart;

    @Option(names = {"-n", "--namespace"}, description = "Target namespace.")
    String namespace;

    @Option(names = {"--http-node-port"}, defaultValue = "30080", description = "NodePort for control-plane HTTP API.")
    int httpNodePort;

    @Option(names = {"--actuator-node-port"}, defaultValue = "30081", description = "NodePort for actuator/metrics.")
    int actuatorNodePort;

    @Option(names = {"--control-plane-tag"}, description = "Override control-plane image tag (Helm value controlPlane.image.tag).")
    String controlPlaneTag;

    @Option(names = {"--control-plane-repository"}, description = "Override control-plane image repository (Helm value controlPlane.image.repository).")
    String controlPlaneRepository;

    @Option(names = {"--control-plane-pull-policy"}, description = "Override control-plane image pull policy (Helm value controlPlane.image.pullPolicy).")
    String controlPlanePullPolicy;

    @Option(names = {"--demos-enabled"}, description = "Enable/disable demo function registration job (Helm value demos.enabled).")
    Boolean demosEnabled;

    @Override
    public void run() {
        String ns = parent.resolveNamespace(namespace);
        parent.runHelmCommand(PlatformCommand.installCommand(
                release,
                chart,
                ns,
                httpNodePort,
                actuatorNodePort,
                controlPlaneTag,
                controlPlaneRepository,
                controlPlanePullPolicy,
                demosEnabled
        ));

        KubernetesClient client = parent.client();
        String endpoint = parent.resolveEndpoint(client, ns, httpNodePort);
        parent.saveResolvedContext(ns, endpoint, client);

        System.out.printf("release\t%s%n", release);
        System.out.printf("namespace\t%s%n", ns);
        System.out.printf("endpoint\t%s%n", endpoint);
    }
}
