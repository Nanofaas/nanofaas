package it.unimib.datai.nanofaas.cli.commands.controlplane;

import it.unimib.datai.nanofaas.cli.http.BuildMetadata;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneCapabilities;
import it.unimib.datai.nanofaas.cli.http.HttpJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "info", mixinStandardHelpOptions = true,
        description = "Show control-plane build identity and capabilities.")
public class ControlPlaneInfoCommand implements Runnable {

    @ParentCommand
    ControlPlaneCommand parent;

    @Override
    public void run() {
        ControlPlaneCapabilities capabilities = parent.root.controlPlaneClient().capabilities();
        BuildMetadata metadata = capabilities.buildMetadata()
                ? parent.root.controlPlaneClient().buildMetadataOrNull()
                : null;
        System.out.println(new HttpJson().toJson(new Info(metadata, capabilities)));
    }

    record Info(BuildMetadata metadata, ControlPlaneCapabilities capabilities) {}
}
