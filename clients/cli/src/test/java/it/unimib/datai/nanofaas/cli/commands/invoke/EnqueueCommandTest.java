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
import java.io.PrintWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnqueueCommandTest {

    private static final String OPENAPI_WITH_ASYNC = """
            openapi: 3.0.0
            paths:
              /v1/functions/{name}:enqueue:
                post:
                  summary: Enqueue an async invocation
                  responses:
                    '202':
                      description: Accepted
            """;

    private static final String OPENAPI_WITHOUT_ASYNC = """
            openapi: 3.0.0
            paths:
              /v1/functions/{name}:enqueue:
                post:
                  summary: Enqueue an async invocation
                  responses:
                    '501':
                      description: Async queue unavailable
            """;

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

    private MockResponse openApiResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(body);
    }

    @Test
    void enqueueWithInlineData() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITH_ASYNC));
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
            assertThat(exit).isZero();
        } finally {
            System.setOut(prev);
        }

        assertThat(server.takeRequest().getPath()).isEqualTo("/openapi.yaml");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo:enqueue");

        assertThat(out.toString()).contains("\"executionId\":\"eq-1\"");
    }

    @Test
    void enqueueWithOptionalHeaders() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITH_ASYNC));
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
        assertThat(exit).isZero();

        assertThat(server.takeRequest().getPath()).isEqualTo("/openapi.yaml");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("idem-async");
        assertThat(req.getHeader("X-Trace-Id")).isEqualTo("trace-async");
    }

    @Test
    void enqueueWithFileData() throws Exception {
        Path inputFile = tmp.resolve("input.json");
        java.nio.file.Files.writeString(inputFile, "{\"msg\":\"from-file\"}");

        server.enqueue(openApiResponse(OPENAPI_WITH_ASYNC));
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
            assertThat(exit).isZero();
        } finally {
            System.setOut(prev);
        }

        assertThat(server.takeRequest().getPath()).isEqualTo("/openapi.yaml");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getBody().readUtf8()).contains("\"msg\":\"from-file\"");
    }

    @Test
    void enqueueWithStdinData() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITH_ASYNC));
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
            assertThat(exit).isZero();
        } finally {
            System.setOut(prev);
            System.setIn(prevIn);
        }

        assertThat(server.takeRequest().getPath()).isEqualTo("/openapi.yaml");
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"from\":\"stdin\"");
    }

    @Test
    void enqueueWithNonExistentFileExitsNonZero() {
        server.enqueue(openApiResponse(OPENAPI_WITH_ASYNC));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        cli.setExpandAtFiles(false);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "@/nonexistent/path/file.json"
        );
        assertThat(exit).isNotZero();
    }

    @Test
    void enqueueWithInvalidJsonExitsNonZero() {
        server.enqueue(openApiResponse(OPENAPI_WITH_ASYNC));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "not-valid-json{{"
        );
        assertThat(exit).isNotZero();
    }

    @Test
    void enqueueWhenAsyncUnsupportedMakesOnlyContractRequestAndReportsError() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITHOUT_ASYNC));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "{\"msg\":\"async\"}"
        );

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Asynchronous invocation is not supported by this control-plane build");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getPath()).isEqualTo("/openapi.yaml");
    }
}
