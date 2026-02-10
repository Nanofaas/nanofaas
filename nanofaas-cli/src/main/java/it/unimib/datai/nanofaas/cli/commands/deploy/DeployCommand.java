package it.unimib.datai.nanofaas.cli.commands.deploy;

import it.unimib.datai.nanofaas.cli.build.BuildSpec;
import it.unimib.datai.nanofaas.cli.build.BuildSpecLoader;
import it.unimib.datai.nanofaas.cli.build.DockerBuildx;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneHttpException;
import it.unimib.datai.nanofaas.cli.io.YamlIO;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;

@Command(name = "deploy", description = "Build+push image (docker buildx) and apply the function spec.")
public class DeployCommand implements Runnable {

    @ParentCommand
    RootCommand root;

    @Option(names = {"-f", "--file"}, required = true, description = "Path to function YAML (includes x-cli.build).")
    Path file;

    @Override
    public void run() {
        FunctionSpec desired = YamlIO.read(file, FunctionSpec.class);
        if (desired.image() == null || desired.image().isBlank()) {
            throw new IllegalArgumentException("Missing image in function spec: " + file);
        }

        BuildSpec build = BuildSpecLoader.load(file);
        DockerBuildx.run(desired.image(), build);

        try {
            root.controlPlaneClient().registerFunction(desired);
            return;
        } catch (ControlPlaneHttpException e) {
            if (e.status() != 409) {
                throw e;
            }
        }

        FunctionSpec existing = root.controlPlaneClient().getFunctionOrNull(desired.name());
        if (existing == null) {
            root.controlPlaneClient().registerFunction(desired);
            return;
        }

        if (!existing.equals(desired)) {
            root.controlPlaneClient().deleteFunction(desired.name());
            root.controlPlaneClient().registerFunction(desired);
        }
    }
}
