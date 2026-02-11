package it.unimib.datai.nanofaas.cli.commands.invoke;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InvokeCommandTest {

    private MockWebServer server;

    @TempDir
    Path tmp;

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
    void invokeWithInlineJson() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":{\"msg\":\"hi\"}}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "invoke", "echo",
                    "-d", "{\"message\":\"hello\"}"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo:invoke");

        String output = out.toString();
        assertThat(output).contains("\"executionId\":\"e1\"");
    }

    @Test
    void invokeWithFileData() throws Exception {
        Path inputFile = tmp.resolve("input.json");
        Files.writeString(inputFile, "{\"key\":\"value\"}");

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e2\",\"status\":\"success\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        cli.setExpandAtFiles(false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "invoke", "echo",
                    "-d", "@" + inputFile
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"key\":\"value\"");
    }

    @Test
    void invokeWithOptionalHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e3\",\"status\":\"ok\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "invoke", "echo",
                "-d", "{\"x\":1}",
                "--timeout-ms", "5000",
                "--idempotency-key", "idem-1",
                "--trace-id", "trace-1"
        );
        assertThat(exit).isEqualTo(0);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("idem-1");
        assertThat(req.getHeader("X-Trace-Id")).isEqualTo("trace-1");
        assertThat(req.getHeader("X-Timeout-Ms")).isEqualTo("5000");
    }

    @Test
    void invokeWithStdinData() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e-stdin\",\"status\":\"success\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        cli.setExpandAtFiles(false);

        java.io.InputStream prevIn = System.in;
        System.setIn(new java.io.ByteArrayInputStream("{\"from\":\"stdin\"}".getBytes()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "invoke", "echo",
                    "-d", "@-"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
            System.setIn(prevIn);
        }

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"from\":\"stdin\"");
    }

    @Test
    void invokeWithNonExistentFileExitsNonZero() {
        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        cli.setExpandAtFiles(false);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "invoke", "echo",
                "-d", "@/nonexistent/path/file.json"
        );
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void invokeWithInvalidJsonExitsNonZero() {
        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "invoke", "echo",
                "-d", "<<<not json>>>"
        );
        assertThat(exit).isNotEqualTo(0);
    }
}
