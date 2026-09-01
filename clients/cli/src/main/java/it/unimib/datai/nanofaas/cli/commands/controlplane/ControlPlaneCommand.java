package it.unimib.datai.nanofaas.cli.commands.controlplane;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.commands.controlplane.config.ControlPlaneConfigCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "control-plane",
        mixinStandardHelpOptions = true,
        description = "Inspect the control-plane build and API contract.",
        subcommands = {
                ControlPlaneInfoCommand.class,
                ControlPlaneContractCommand.class,
                ControlPlaneConfigCommand.class
        }
)
public class ControlPlaneCommand {

    @ParentCommand
    public RootCommand root;
}
