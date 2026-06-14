package it.unimib.datai.nanofaas.cli.commands.fn;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "delete", description = "Delete a function by name.")
public class FnDeleteCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Override
    public void run() {
        parent.root.controlPlaneClient().deleteFunction(name);
    }
}
