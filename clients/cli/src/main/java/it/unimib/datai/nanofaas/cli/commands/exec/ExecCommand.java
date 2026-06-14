package it.unimib.datai.nanofaas.cli.commands.exec;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "exec",
        description = "Manage executions.",
        subcommands = {ExecGetCommand.class}
)
public class ExecCommand {

    @ParentCommand
    RootCommand root;
}
