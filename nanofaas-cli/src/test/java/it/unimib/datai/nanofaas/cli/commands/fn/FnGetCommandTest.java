package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.testsupport.CliTestSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class FnGetCommandTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void getFoundFunctionPrintsSpec() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"img/echo:1\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "fn", "get", "echo");
        assertThat(result.exitCode()).isEqualTo(0);

        assertThat(result.stdout()).contains("echo");
    }

    @Test
    void getNotFoundExitsNonZero() {
        server.enqueue(new MockResponse().setResponseCode(404));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "get", "missing");

        assertThat(exit).isNotEqualTo(0);
    }
}
