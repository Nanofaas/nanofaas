package it.unimib.datai.nanofaas.cli.commands.fn;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "replicas",
        mixinStandardHelpOptions = true,
        description = "Manage the replica count of a function.",
        subcommands = {
                FnReplicasGetCommand.class,
                FnReplicasSetCommand.class
        }
)
public class FnReplicasCommand {

    @ParentCommand
    FnCommand parent;
}
