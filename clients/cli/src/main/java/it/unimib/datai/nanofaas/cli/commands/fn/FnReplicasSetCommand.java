package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.http.HttpJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "set", mixinStandardHelpOptions = true,
        description = "Set the desired replica count of a function.")
public class FnReplicasSetCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnReplicasCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Parameters(index = "1", description = "Desired replica count")
    int replicas;

    @Override
    public void run() {
        if (replicas < 0) {
            throw new IllegalArgumentException("Replica count must not be negative");
        }
        ControlPlaneClient client = parent.parent.root.controlPlaneClient();
        if (!client.capabilities().replicas()) {
            throw new IllegalStateException(
                    "Replica management is not supported by this control-plane build");
        }
        System.out.println(new HttpJson().toJson(client.setReplicas(name, replicas)));
    }
}
