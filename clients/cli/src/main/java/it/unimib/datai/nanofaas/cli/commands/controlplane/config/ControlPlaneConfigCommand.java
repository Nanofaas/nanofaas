package it.unimib.datai.nanofaas.cli.commands.controlplane.config;

import it.unimib.datai.nanofaas.cli.commands.controlplane.ControlPlaneCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "config",
        mixinStandardHelpOptions = true,
        description = "Administer control-plane runtime configuration.",
        subcommands = {
                ControlPlaneConfigGetCommand.class,
                ControlPlaneConfigValidateCommand.class,
                ControlPlaneConfigPatchCommand.class
        }
)
public class ControlPlaneConfigCommand {

    @ParentCommand
    ControlPlaneCommand parent;
}
