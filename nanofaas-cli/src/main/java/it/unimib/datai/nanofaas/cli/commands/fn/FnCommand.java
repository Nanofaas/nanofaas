package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Command;

@Command(
        name = "fn",
        description = "Manage registered functions.",
        subcommands = {
                FnApplyCommand.class
        }
)
public class FnCommand {

    @ParentCommand
    RootCommand root;
}
