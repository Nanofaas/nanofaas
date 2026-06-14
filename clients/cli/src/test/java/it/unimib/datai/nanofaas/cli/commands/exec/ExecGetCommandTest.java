package it.unimib.datai.nanofaas.cli.commands.exec;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExecGetCommandTest {

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
    void basicGetPrintsJson() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-1\",\"status\":\"success\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "exec", "get", "exec-1"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/executions/exec-1");
        assertThat(out.toString()).contains("\"executionId\":\"exec-1\"");
    }

    @Test
    void watchWithTerminalStateExitsImmediately() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-2\",\"status\":\"success\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "exec", "get", "exec-2", "--watch"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        // Only 1 request should have been made since status is already terminal
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(out.toString()).contains("\"status\":\"success\"");
    }

    @Test
    void watchPollsUntilTerminal() throws Exception {
        // First poll: "running" (non-terminal)
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-3\",\"status\":\"running\"}"));
        // Second poll: "error" (terminal)
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-3\",\"status\":\"error\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "exec", "get", "exec-3",
                    "--watch", "--interval", "PT0.1S", "--timeout", "PT10S"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        assertThat(server.getRequestCount()).isEqualTo(2);
        String output = out.toString();
        assertThat(output).contains("\"status\":\"running\"");
        assertThat(output).contains("\"status\":\"error\"");
    }

    @Test
    void watchExitsOnTimeoutTerminalState() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-4\",\"status\":\"timeout\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "exec", "get", "exec-4", "--watch"
        );
        assertThat(exit).isEqualTo(0);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void watchDeadlineExceededExitsNonZero() throws Exception {
        // Always return non-terminal status
        for (int i = 0; i < 20; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{\"executionId\":\"exec-5\",\"status\":\"running\"}"));
        }

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "exec", "get", "exec-5",
                "--watch", "--interval", "PT0.05S", "--timeout", "PT0.2S"
        );
        assertThat(exit).isNotEqualTo(0);
    }
}
