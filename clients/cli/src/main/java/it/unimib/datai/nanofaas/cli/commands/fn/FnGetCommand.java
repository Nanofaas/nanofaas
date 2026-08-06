package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.FunctionDetails;
import it.unimib.datai.nanofaas.cli.http.HttpJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "get", mixinStandardHelpOptions = true, description = "Get function details by name.")
public class FnGetCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Override
    public void run() {
        FunctionDetails function = parent.root.controlPlaneClient().getFunctionOrNull(name);
        if (function == null) {
            throw new IllegalArgumentException("Function not found: " + name);
        }
        System.out.println(new HttpJson().toJson(function));
    }
}
