package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "get", "echo");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        assertThat(out.toString()).contains("echo");
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
