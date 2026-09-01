package it.unimib.datai.nanofaas.cli.commands.controlplane;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "contract", mixinStandardHelpOptions = true,
        description = "Print the control-plane OpenAPI contract unchanged.")
public class ControlPlaneContractCommand implements Runnable {

    @ParentCommand
    ControlPlaneCommand parent;

    @Override
    public void run() {
        System.out.print(parent.root.controlPlaneClient().openApi());
    }
}
