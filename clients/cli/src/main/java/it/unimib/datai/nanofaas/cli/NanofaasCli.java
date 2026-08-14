package it.unimib.datai.nanofaas.cli;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import picocli.CommandLine;

public final class NanofaasCli {
    private NanofaasCli() {}

    public static void main(String[] args) {
        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }

    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new RootCommand());
        cli.setExpandAtFiles(false);
        cli.setExecutionExceptionHandler((exception, commandLine, parseResult) -> {
            commandLine.getErr().println("Error: " + exception.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        });
        return cli;
    }
}
