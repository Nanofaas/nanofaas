package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneHttpException;
import it.unimib.datai.nanofaas.cli.io.YamlIO;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;

@Command(name = "apply", description = "Create or replace a function from a YAML spec.")
public class FnApplyCommand implements Runnable {

    @ParentCommand
    FnCommand parent;

    @Option(names = {"-f", "--file"}, required = true, description = "Path to function YAML.")
    Path file;

    @Override
    public void run() {
        FunctionSpec desired = YamlIO.read(file, FunctionSpec.class);

        try {
            parent.root.controlPlaneClient().registerFunction(desired);
            return;
        } catch (ControlPlaneHttpException e) {
            if (e.status() != 409) {
                throw e;
            }
        }

        FunctionSpec existing = parent.root.controlPlaneClient().getFunctionOrNull(desired.name());
        if (existing == null) {
            // If the server says conflict but GET can't find it, retry create.
            parent.root.controlPlaneClient().registerFunction(desired);
            return;
        }

        if (!existing.equals(desired)) {
            parent.root.controlPlaneClient().deleteFunction(desired.name());
            parent.root.controlPlaneClient().registerFunction(desired);
        }
    }
}
