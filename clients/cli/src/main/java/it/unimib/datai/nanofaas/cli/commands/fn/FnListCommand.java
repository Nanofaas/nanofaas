package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.FunctionDetails;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "list", mixinStandardHelpOptions = true, description = "List registered functions.")
public class FnListCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnCommand parent;

    @Override
    public void run() {
        List<FunctionDetails> functions = parent.root.controlPlaneClient().listFunctions();
        for (FunctionDetails f : functions) {
            System.out.printf("%s\t%s%n", f.name(), f.image());
        }
    }
}
