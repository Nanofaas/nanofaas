package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "list", description = "List registered functions.")
public class FnListCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    FnCommand parent;

    @Override
    public void run() {
        List<FunctionSpec> functions = parent.root.controlPlaneClient().listFunctions();
        for (FunctionSpec f : functions) {
            System.out.printf("%s\t%s%n", f.name(), f.image());
        }
    }
}
