package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "get", description = "Get a function spec by name.")
public class FnGetCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Override
    public void run() {
        FunctionSpec spec = parent.root.controlPlaneClient().getFunctionOrNull(name);
        if (spec == null) {
            throw new IllegalArgumentException("Function not found: " + name);
        }
        // For now print JSON-ish via record toString; we'll switch to JSON output later.
        System.out.println(spec);
    }
}
