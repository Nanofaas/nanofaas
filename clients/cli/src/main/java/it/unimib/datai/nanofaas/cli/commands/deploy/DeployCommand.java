package it.unimib.datai.nanofaas.cli.commands.deploy;

import it.unimib.datai.nanofaas.cli.image.BuildSpec;
import it.unimib.datai.nanofaas.cli.image.BuildSpecLoader;
import it.unimib.datai.nanofaas.cli.image.DockerBuildx;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneError;
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
                throw mapApplyError(e);
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

    private static RuntimeException mapApplyError(ControlPlaneHttpException e) {
        ControlPlaneError err = ControlPlaneError.fromBody(e.body());
        String code = err.code();
        if ("IMAGE_NOT_FOUND".equals(code)) {
            return new IllegalArgumentException("Image not found in registry. Check image name/tag and retry.");
        }
        if ("IMAGE_PULL_AUTH_REQUIRED".equals(code)) {
            return new IllegalArgumentException("Image pull authentication failed. Configure Kubernetes imagePullSecrets and retry.");
        }
        if ("IMAGE_REGISTRY_UNAVAILABLE".equals(code)) {
            return new IllegalArgumentException("Registry unavailable while validating image. Retry later.");
        }
        return e;
    }
}
