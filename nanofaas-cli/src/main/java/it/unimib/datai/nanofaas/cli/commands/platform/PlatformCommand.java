package it.unimib.datai.nanofaas.cli.commands.platform;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.config.Config;
import it.unimib.datai.nanofaas.cli.config.Context;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Command(
        name = "platform",
        mixinStandardHelpOptions = true,
        description = "Install, inspect, and uninstall nanofaas on Kubernetes via Helm.",
        subcommands = {
                PlatformInstallCommand.class,
                PlatformStatusCommand.class,
                PlatformUninstallCommand.class
        }
)
public class PlatformCommand {

    @ParentCommand
    RootCommand root;

    private final Supplier<KubernetesClient> clientSupplier;
    private final Consumer<List<String>> helmRunner;

    public PlatformCommand() {
        this(() -> new KubernetesClientBuilder().build(), PlatformCommand::runHelm);
    }

    // For tests.
    PlatformCommand(Supplier<KubernetesClient> clientSupplier, Consumer<List<String>> helmRunner) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.helmRunner = Objects.requireNonNull(helmRunner, "helmRunner");
    }

    KubernetesClient client() {
        return clientSupplier.get();
    }

    void runHelmCommand(List<String> cmd) {
        helmRunner.accept(cmd);
    }

    String resolveNamespace(String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (root != null) {
            String ns = root.resolvedContext().namespace();
            if (ns != null && !ns.isBlank()) {
                return ns;
            }
        }
        return "nanofaas";
    }

    String resolveNodeAddress(KubernetesClient client) {
        List<Node> nodes = client.nodes().list().getItems();

        String external = findNodeAddress(nodes, "ExternalIP");
        if (external != null) {
            return external;
        }

        String internal = findNodeAddress(nodes, "InternalIP");
        if (internal != null) {
            return internal;
        }

        return "127.0.0.1";
    }

    String resolveEndpoint(KubernetesClient client, String namespace, int fallbackHttpPort) {
        Service svc = client.services().inNamespace(namespace).withName("control-plane").get();
        if (svc == null) {
            throw new IllegalStateException("control-plane service not found in namespace " + namespace);
        }

        String type = (svc.getSpec() == null || svc.getSpec().getType() == null || svc.getSpec().getType().isBlank())
                ? "ClusterIP"
                : svc.getSpec().getType();

        if ("NodePort".equals(type)) {
            Integer nodePort = servicePort(svc, "http").map(ServicePort::getNodePort).orElse(null);
            int port = (nodePort == null || nodePort <= 0) ? fallbackHttpPort : nodePort;
            return "http://" + resolveNodeAddress(client) + ":" + port;
        }

        int port = servicePort(svc, "http").map(ServicePort::getPort).orElse(fallbackHttpPort);
        return "http://control-plane." + namespace + ".svc.cluster.local:" + port;
    }

    void saveResolvedContext(String namespace, String endpoint, KubernetesClient client) {
        if (root == null) {
            return;
        }

        Config cfg = root.configStore().load();
        String contextName = firstNonBlank(root.resolvedContext().contextName(), kubeContextName(client), "k8s");

        Map<String, Context> contexts = cfg.getContexts();
        Context ctx = contexts.get(contextName);
        if (ctx == null) {
            ctx = new Context();
        }
        ctx.setNamespace(namespace);
        ctx.setEndpoint(endpoint);
        contexts.put(contextName, ctx);

        cfg.setCurrentContext(contextName);
        cfg.setContexts(contexts);
        root.configStore().save(cfg);
    }

    void clearEndpointForCurrentContext(String namespace) {
        if (root == null) {
            return;
        }

        String contextName = root.resolvedContext().contextName();
        if (contextName == null || contextName.isBlank()) {
            return;
        }

        Config cfg = root.configStore().load();
        Context ctx = cfg.getContexts().get(contextName);
        if (ctx == null) {
            return;
        }

        String ctxNs = ctx.getNamespace();
        if (ctxNs == null || ctxNs.isBlank() || ctxNs.equals(namespace)) {
            ctx.setEndpoint(null);
            if (ctxNs == null || ctxNs.isBlank()) {
                ctx.setNamespace(namespace);
            }
            cfg.getContexts().put(contextName, ctx);
            root.configStore().save(cfg);
        }
    }

    static List<String> installCommand(
            String release,
            String chart,
            String namespace,
            int httpNodePort,
            int actuatorNodePort,
            String controlPlaneTag
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add("helm");
        cmd.add("upgrade");
        cmd.add("--install");
        cmd.add(release);
        cmd.add(chart);
        cmd.add("--namespace");
        cmd.add(namespace);
        cmd.add("--create-namespace");
        cmd.add("--wait");
        cmd.add("--set");
        cmd.add("namespace.create=false");
        cmd.add("--set");
        cmd.add("controlPlane.service.type=NodePort");
        cmd.add("--set");
        cmd.add("controlPlane.service.nodePorts.http=" + httpNodePort);
        cmd.add("--set");
        cmd.add("controlPlane.service.nodePorts.actuator=" + actuatorNodePort);
        if (controlPlaneTag != null && !controlPlaneTag.isBlank()) {
            cmd.add("--set");
            cmd.add("controlPlane.image.tag=" + controlPlaneTag);
        }
        return cmd;
    }

    static List<String> uninstallCommand(String release, String namespace) {
        return List.of("helm", "uninstall", release, "--namespace", namespace);
    }

    private static void runHelm(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        try {
            Process process = pb.start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("helm failed with exit code " + exit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running helm", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run helm", e);
        }
    }

    private static java.util.Optional<ServicePort> servicePort(Service svc, String name) {
        if (svc.getSpec() == null || svc.getSpec().getPorts() == null) {
            return java.util.Optional.empty();
        }
        return svc.getSpec().getPorts().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst();
    }

    private static String findNodeAddress(List<Node> nodes, String type) {
        return nodes.stream()
                .flatMap(n -> n.getStatus() == null || n.getStatus().getAddresses() == null
                        ? java.util.stream.Stream.<NodeAddress>empty()
                        : n.getStatus().getAddresses().stream())
                .filter(a -> type.equals(a.getType()) && a.getAddress() != null && !a.getAddress().isBlank())
                .map(NodeAddress::getAddress)
                .sorted(Comparator.naturalOrder())
                .findFirst()
                .orElse(null);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String kubeContextName(KubernetesClient client) {
        try {
            io.fabric8.kubernetes.client.Config config = client.getConfiguration();
            if (config != null && config.getCurrentContext() != null) {
                String name = config.getCurrentContext().getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // Ignore and fallback.
        }
        return null;
    }
}
