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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnqueueCommandTest {

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
    void enqueueWithInlineData() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"eq-1\",\"status\":\"queued\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "enqueue", "echo",
                    "-d", "{\"msg\":\"async\"}"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo:enqueue");

        assertThat(out.toString()).contains("\"executionId\":\"eq-1\"");
    }

    @Test
    void enqueueWithOptionalHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"eq-2\",\"status\":\"queued\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "{\"x\":1}",
                "--idempotency-key", "idem-async",
                "--trace-id", "trace-async"
        );
        assertThat(exit).isEqualTo(0);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("idem-async");
        assertThat(req.getHeader("X-Trace-Id")).isEqualTo("trace-async");
    }

    @Test
    void enqueueWithFileData() throws Exception {
        Path inputFile = tmp.resolve("input.json");
        java.nio.file.Files.writeString(inputFile, "{\"msg\":\"from-file\"}");

        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"eq-f\",\"status\":\"queued\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        cli.setExpandAtFiles(false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "enqueue", "echo",
                    "-d", "@" + inputFile
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
        }

        RecordedRequest req = server.takeRequest();
        assertThat(req.getBody().readUtf8()).contains("\"msg\":\"from-file\"");
    }

    @Test
    void enqueueWithStdinData() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"eq-stdin\",\"status\":\"queued\"}"));

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
                    "enqueue", "echo",
                    "-d", "@-"
            );
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(prev);
            System.setIn(prevIn);
        }

        okhttp3.mockwebserver.RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"from\":\"stdin\"");
    }

    @Test
    void enqueueWithNonExistentFileExitsNonZero() {
        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        cli.setExpandAtFiles(false);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "@/nonexistent/path/file.json"
        );
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void enqueueWithInvalidJsonExitsNonZero() {
        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "not-valid-json{{"
        );
        assertThat(exit).isNotEqualTo(0);
    }
}
