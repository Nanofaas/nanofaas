package it.unimib.datai.nanofaas.cli.commands.controlplane.config;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneHttpException;
import it.unimib.datai.nanofaas.cli.http.HttpJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "get", mixinStandardHelpOptions = true,
        description = "Print runtime configuration for a namespace, or the whole snapshot.")
public class ControlPlaneConfigGetCommand implements Runnable {

    @ParentCommand
    ControlPlaneConfigCommand parent;

    @Parameters(index = "0", arity = "0..1", description = "Runtime-config namespace (omit for the whole snapshot).")
    String namespace;

    @Override
    public void run() {
        ControlPlaneClient client = parent.parent.root.controlPlaneClient();
        if (!client.capabilities().runtimeConfig()) {
            throw new IllegalStateException(
                    "Runtime configuration is not supported by this control-plane build");
        }
        try {
            if (namespace == null) {
                System.out.println(new HttpJson().toJson(client.getRuntimeConfig()));
            } else {
                System.out.println(new HttpJson().toJson(client.getRuntimeConfig(namespace)));
            }
        } catch (ControlPlaneHttpException e) {
            throw RuntimeConfigErrorMapper.map(e);
        }
    }
}
