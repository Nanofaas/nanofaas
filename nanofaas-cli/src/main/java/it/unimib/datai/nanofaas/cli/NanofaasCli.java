package it.unimib.datai.nanofaas.cli;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import picocli.CommandLine;

public final class NanofaasCli {
    private NanofaasCli() {}

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new RootCommand());
        cli.setExpandAtFiles(false);
        int exitCode = cli.execute(args);
        System.exit(exitCode);
    }
}

