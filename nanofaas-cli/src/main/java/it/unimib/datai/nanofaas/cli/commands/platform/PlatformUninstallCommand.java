package it.unimib.datai.nanofaas.cli.commands.platform;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "uninstall", description = "Uninstall nanofaas Helm release.")
public class PlatformUninstallCommand implements Runnable {

    @ParentCommand
    PlatformCommand parent;

    @Option(names = {"--release"}, defaultValue = "nanofaas", description = "Helm release name.")
    String release;

    @Option(names = {"-n", "--namespace"}, description = "Target namespace.")
    String namespace;

    @Override
    public void run() {
        String ns = parent.resolveNamespace(namespace);
        parent.runHelmCommand(PlatformCommand.uninstallCommand(release, ns));
        parent.clearEndpointForCurrentContext(ns);

        System.out.printf("release\t%s%n", release);
        System.out.printf("namespace\t%s%n", ns);
        System.out.println("uninstalled\ttrue");
    }
}
