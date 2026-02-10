package it.unimib.datai.nanofaas.cli.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

class RootCommandTest {

    @Test
    void helpPrintsUsage() {
        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(out, true));

        int exit = cli.execute("--help");

        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("Usage:");
    }
}

