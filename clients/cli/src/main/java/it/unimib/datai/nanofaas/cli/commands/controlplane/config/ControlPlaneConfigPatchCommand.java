package it.unimib.datai.nanofaas.cli.commands.controlplane.config;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneHttpException;
import it.unimib.datai.nanofaas.cli.http.HttpJson;
import it.unimib.datai.nanofaas.cli.http.RuntimeConfigPatchRequest;
import it.unimib.datai.nanofaas.cli.http.RuntimeConfigPatchResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "patch", mixinStandardHelpOptions = true,
        description = "Patch a runtime-configuration namespace.")
public class ControlPlaneConfigPatchCommand implements Runnable {

    @ParentCommand
    ControlPlaneConfigCommand parent;

    @Parameters(index = "0", description = "Runtime-config namespace.")
    String namespace;

    @Option(names = {"-f", "--file"}, required = true, description = "Path to values YAML.")
    Path file;

    @Override
    public void run() {
        ControlPlaneClient client = parent.parent.root.controlPlaneClient();
        if (!client.capabilities().runtimeConfig()) {
            throw new IllegalStateException(
                    "Runtime configuration is not supported by this control-plane build");
        }
        RuntimeConfigInput input = RuntimeConfigInput.load(file);
        try {
            long revision = input.expectedRevision() != null
                    ? input.expectedRevision()
                    : client.getRuntimeConfig().revision();
            RuntimeConfigPatchResponse response = client.patchRuntimeConfig(
                    namespace, new RuntimeConfigPatchRequest(revision, input.values()));
            System.out.println(new HttpJson().toJson(response));
        } catch (ControlPlaneHttpException e) {
            throw RuntimeConfigErrorMapper.map(e);
        }
    }
}
