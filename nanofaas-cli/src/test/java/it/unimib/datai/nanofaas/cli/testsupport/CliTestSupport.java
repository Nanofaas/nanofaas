package it.unimib.datai.nanofaas.cli.testsupport;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class CliTestSupport {

    private CliTestSupport() {
    }

    public static CommandResult executeAndCaptureStdout(CommandLine cli, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(args);
            return new CommandResult(exit, out.toString());
        } finally {
            System.setOut(prev);
        }
    }

    public record CommandResult(int exitCode, String stdout) {
    }
}
