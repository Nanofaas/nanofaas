package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.http.HttpJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "get", mixinStandardHelpOptions = true,
        description = "Get the managed replica status of a function.")
public class FnReplicasGetCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnReplicasCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Override
    public void run() {
        ControlPlaneClient client = parent.parent.root.controlPlaneClient();
        if (!client.capabilities().replicas()) {
            throw new IllegalStateException(
                    "Replica management is not supported by this control-plane build");
        }
        System.out.println(new HttpJson().toJson(client.getReplicas(name)));
    }
}
