package it.unimib.datai.nanofaas.cli;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import picocli.CommandLine;

public final class NanofaasCli {
    private NanofaasCli() {}

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RootCommand()).execute(args);
        System.exit(exitCode);
    }
}

