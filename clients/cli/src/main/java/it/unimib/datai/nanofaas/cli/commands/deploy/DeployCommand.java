package it.unimib.datai.nanofaas.cli.commands.deploy;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.commands.fn.FunctionApplier;
import it.unimib.datai.nanofaas.cli.image.BuildSpec;
import it.unimib.datai.nanofaas.cli.image.BuildSpecLoader;
import it.unimib.datai.nanofaas.cli.image.DockerBuildx;
import it.unimib.datai.nanofaas.cli.io.YamlIO;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;

@Command(name = "deploy", mixinStandardHelpOptions = true,
        description = "Build+push image (docker buildx) and apply the function spec.")
public class DeployCommand implements Runnable {

    @ParentCommand
    RootCommand root;

    @Option(names = {"-f", "--file"}, required = true, description = "Path to function YAML (includes x-cli.build).")
    Path file;

    @Option(names = {"--runtime"},
            defaultValue = "docker",
            description = "Container runtime to use (docker or podman).")
    String runtime;

    @Option(names = {"--replace"},
            description = "Allow destructive replacement (DELETE+POST) of immutable fields.")
    boolean replace;

    @Override
    public void run() {
        FunctionSpec desired = YamlIO.read(file, FunctionSpec.class);
        if (desired.image() == null || desired.image().isBlank()) {
            throw new IllegalArgumentException("Missing image in function spec: " + file);
        }

        BuildSpec build = BuildSpecLoader.load(file);
        DockerBuildx.run(desired.image(), build, runtime);

        FunctionApplier.apply(root.controlPlaneClient(), desired, replace);
    }
}
