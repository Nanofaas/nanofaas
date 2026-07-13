package it.unimib.datai.nanofaas.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class NanofaasCliTest {
    @Test
    void executionErrorsAreConcise() throws Exception {
        Method factory = NanofaasCli.class.getDeclaredMethod("commandLine");
        factory.setAccessible(true);
        CommandLine cli = (CommandLine) factory.invoke(null);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("fn", "list");

        assertThat(exit).isNotZero();
        assertThat(err.toString())
                .contains("Error: Missing endpoint")
                .doesNotContain("IllegalArgumentException")
                .doesNotContain("\tat ");
    }
}
