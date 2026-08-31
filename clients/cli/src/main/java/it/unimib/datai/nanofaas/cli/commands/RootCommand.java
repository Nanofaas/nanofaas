package it.unimib.datai.nanofaas.cli.commands;

import it.unimib.datai.nanofaas.cli.commands.controlplane.ControlPlaneCommand;
import it.unimib.datai.nanofaas.cli.commands.fn.FnCommand;
import it.unimib.datai.nanofaas.cli.config.ConfigStore;
import it.unimib.datai.nanofaas.cli.config.ResolvedContext;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.commands.exec.ExecCommand;
import it.unimib.datai.nanofaas.cli.commands.deploy.DeployCommand;
import it.unimib.datai.nanofaas.cli.commands.invoke.EnqueueCommand;
import it.unimib.datai.nanofaas.cli.commands.invoke.InvokeCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.nio.file.Path;

@Command(
        name = "nanofaas",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Nanofaas control-plane client.",
        subcommands = {
                FnCommand.class,
                InvokeCommand.class,
                EnqueueCommand.class,
                ExecCommand.class,
                DeployCommand.class,
                ControlPlaneCommand.class
        }
)
public class RootCommand {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8080";

    @Option(names = {"--config"}, scope = ScopeType.INHERIT,
            description = "Path to config file (default: ~/.config/nanofaas/config.yaml).")
    Path configPath;

    @Option(names = {"--endpoint"},
            scope = ScopeType.INHERIT,
            description = "Control-plane base URL (overrides config/env). Default: http://localhost:8080")
    String endpoint;

    private ConfigStore store;
    private ResolvedContext resolved;
    private ControlPlaneClient client;

    public ConfigStore configStore() {
        if (store == null) {
            store = (configPath == null) ? new ConfigStore() : new ConfigStore(configPath);
        }
        return store;
    }

    /**
     * Resolves the active context using CLI options, config, and defaults.
     *
     * @return the resolved Nanofaas context
     */
    public ResolvedContext resolvedContext() {
        if (resolved == null) {
            ResolvedContext base = configStore().loadResolvedContext();

            // Add DEFAULT_ENDPOINT at the end as the default value.
            resolved = new ResolvedContext(base.contextName(), firstNonBlank(
                    endpoint,
                    base.endpoint(),
                    DEFAULT_ENDPOINT
            ));
        }
        return resolved;
    }

    public ControlPlaneClient controlPlaneClient() {
        if (client == null) {
            String ep = resolvedContext().endpoint();
            client = new ControlPlaneClient(ep);
        }
        return client;
    }

    /**
     * Returns the first non-null and non-blank value, preserving the given order.
     *
     * @param values candidate values by priority
     * @return the first valid value, or {@code null} if none exists
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
