package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.io.YamlIO;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;

@Command(name = "apply", mixinStandardHelpOptions = true,
        description = "Create or replace a function from a YAML spec.")
public class FnApplyCommand implements Runnable {

    @ParentCommand
    FnCommand parent;

    @Option(names = {"-f", "--file"}, required = true, description = "Path to function YAML.")
    Path file;

    @Override
    public void run() {
        FunctionSpec desired = YamlIO.read(file, FunctionSpec.class);
        FunctionApplier.apply(parent.root.controlPlaneClient(), desired);
    }
}
