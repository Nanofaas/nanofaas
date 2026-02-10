package it.unimib.datai.nanofaas.cli.commands;

import it.unimib.datai.nanofaas.cli.commands.fn.FnCommand;
import it.unimib.datai.nanofaas.cli.config.ConfigStore;
import it.unimib.datai.nanofaas.cli.config.ResolvedContext;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.commands.exec.ExecCommand;
import it.unimib.datai.nanofaas.cli.commands.invoke.EnqueueCommand;
import it.unimib.datai.nanofaas.cli.commands.invoke.InvokeCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Command(
        name = "nanofaas",
        mixinStandardHelpOptions = true,
        description = "Nanofaas CLI (control-plane client + Kubernetes helpers).",
        subcommands = {
                FnCommand.class,
                InvokeCommand.class,
                EnqueueCommand.class,
                ExecCommand.class
        }
)
public class RootCommand {

    @Option(names = {"--config"}, description = "Path to config file (default: ~/.config/nanofaas/config.yaml).")
    Path configPath;

    @Option(names = {"--endpoint"}, description = "Control-plane base URL (overrides config/env).")
    String endpoint;

    @Option(names = {"--namespace", "-n"}, description = "Kubernetes namespace (overrides config/env).")
    String namespace;

    private ConfigStore store;
    private ResolvedContext resolved;
    private ControlPlaneClient client;

    public ConfigStore configStore() {
        if (store == null) {
            store = (configPath == null) ? new ConfigStore() : new ConfigStore(configPath);
        }
        return store;
    }

    public ResolvedContext resolvedContext() {
        if (resolved == null) {
            ResolvedContext base = configStore().loadResolvedContext();
            String ep = firstNonBlank(endpoint, base.endpoint());
            String ns = firstNonBlank(namespace, base.namespace());
            resolved = new ResolvedContext(base.contextName(), ep, ns);
        }
        return resolved;
    }

    public ControlPlaneClient controlPlaneClient() {
        if (client == null) {
            String ep = resolvedContext().endpoint();
            if (ep == null || ep.isBlank()) {
                throw new IllegalArgumentException("Missing endpoint. Set --endpoint or NANOFAAS_ENDPOINT or configure a context.");
            }
            client = new ControlPlaneClient(ep);
        }
        return client;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
