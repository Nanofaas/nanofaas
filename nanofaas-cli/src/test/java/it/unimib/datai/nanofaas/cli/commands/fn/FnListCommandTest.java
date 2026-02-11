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

class FnListCommandTest {

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
    void listReturnsTwoFunctions() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("[{\"name\":\"echo\",\"image\":\"img/echo:1\"},{\"name\":\"greet\",\"image\":\"img/greet:2\"}]"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "list");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        String output = out.toString();
        assertThat(output).contains("echo\timg/echo:1");
        assertThat(output).contains("greet\timg/greet:2");
    }

    @Test
    void listReturnsEmptyWhenNoFunctions() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("[]"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "list");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        assertThat(out.toString().trim()).isEmpty();
    }
}
