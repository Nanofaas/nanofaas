package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.http.FunctionPatch;
import it.unimib.datai.nanofaas.cli.io.YamlIO;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Update mutable fields of a function from a YAML patch.")
public class FnUpdateCommand implements Runnable {

    @ParentCommand
    FnCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Option(names = {"-f", "--file"}, required = true, description = "Path to function patch YAML.")
    Path file;

    @Override
    public void run() {
        ControlPlaneClient client = parent.root.controlPlaneClient();
        if (!client.capabilities().functionUpdate()) {
            throw new IllegalStateException(
                    "Function updates are not supported by this control-plane build");
        }
        FunctionPatch patch = YamlIO.readStrict(file, FunctionPatch.class);
        if (patch.isEmpty()) {
            throw new IllegalArgumentException("Function update is empty");
        }
        client.updateFunction(name, patch);
    }
}
