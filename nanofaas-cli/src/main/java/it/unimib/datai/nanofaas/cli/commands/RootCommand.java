package it.unimib.datai.nanofaas.cli.commands;

import picocli.CommandLine.Command;

@Command(
        name = "nanofaas",
        mixinStandardHelpOptions = true,
        description = "Nanofaas CLI (control-plane client + Kubernetes helpers)."
)
public class RootCommand {
}

