package it.unimib.datai.nanofaas.cli.commands.fn;

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
import java.io.PrintWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FnApplyCommandTest {

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
    void applyFirstRegisterSucceeds() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        java.nio.file.Files.writeString(fn, """
                name: echo
                image: registry.example/echo:1
                """);

        // register -> 201 created (no conflict)
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());
        assertThat(exit).isZero();

        // Only 1 request: POST (201)
        assertThat(server.getRequestCount()).isEqualTo(1);
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions");
    }

    @Test
    void applyOnConflictMutableDifferencePatches() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        String yaml = """
                name: echo
                image: registry.example/echo:1
                timeoutMs: 7000
                """;
        java.nio.file.Files.writeString(fn, yaml);

        // 1) register -> 409 conflict
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) get existing -> same immutable fields, different mutable timeoutMs
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"registry.example/echo:1","timeoutMs":1000,
                         "requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"DEPLOYMENT",
                         "runtimeMode":"HTTP"}
                        """));
        // 3) patch -> 200
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\",\"timeoutMs\":7000}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply",
                "-f", fn.toString()
        );

        assertThat(exit).isZero();

        // POST (409), GET, PATCH — never DELETE
        assertThat(server.getRequestCount()).isEqualTo(3);

        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");
        assertThat(r1.getPath()).isEqualTo("/v1/functions");

        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
        assertThat(r2.getPath()).isEqualTo("/v1/functions/echo");

        RecordedRequest r3 = server.takeRequest();
        assertThat(r3.getMethod()).isEqualTo("PATCH");
        assertThat(r3.getPath()).isEqualTo("/v1/functions/echo");
        assertThat(r3.getBody().readUtf8()).contains("\"timeoutMs\":7000");
    }

    @Test
    void applyOnConflictImmutableDifferenceRequiresReplace() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        String yaml = """
                name: echo
                image: registry.example/echo:2
                """;
        java.nio.file.Files.writeString(fn, yaml);

        // 1) register -> 409 conflict
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) get existing -> different immutable image
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"registry.example/echo:1",
                         "requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"DEPLOYMENT",
                         "runtimeMode":"HTTP"}
                        """));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply",
                "-f", fn.toString()
        );

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Immutable function fields differ");

        // POST (409) + GET only; never DELETE or re-POST
        assertThat(server.getRequestCount()).isEqualTo(2);
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");
        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
        assertThat(r2.getPath()).isEqualTo("/v1/functions/echo");
    }

    @Test
    void applyOnConflictImmutableDifferenceWithReplaceDeletesThenPosts() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        String yaml = """
                name: echo
                image: registry.example/echo:2
                """;
        java.nio.file.Files.writeString(fn, yaml);

        // 1) register -> 409 conflict
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) get existing -> different immutable image
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"registry.example/echo:1",
                         "requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"DEPLOYMENT",
                         "runtimeMode":"HTTP"}
                        """));
        // 3) delete -> 204
        server.enqueue(new MockResponse().setResponseCode(204));
        // 4) register -> 201 created
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:2\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply", "--replace",
                "-f", fn.toString()
        );

        assertThat(exit).isZero();

        assertThat(server.getRequestCount()).isEqualTo(4);
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");
        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
        RecordedRequest r3 = server.takeRequest();
        assertThat(r3.getMethod()).isEqualTo("DELETE");
        assertThat(r3.getPath()).isEqualTo("/v1/functions/echo");
        RecordedRequest r4 = server.takeRequest();
        assertThat(r4.getMethod()).isEqualTo("POST");
        assertThat(r4.getPath()).isEqualTo("/v1/functions");
        assertThat(r4.getBody().readUtf8()).contains("\"image\":\"registry.example/echo:2\"");
    }

    @Test
    void applyOnConflictSkipsReplaceWhenSameSpec() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        String yaml = """
                name: echo
                image: registry.example/echo:1
                timeoutMs: 1000
                """;
        java.nio.file.Files.writeString(fn, yaml);

        // 1) register -> 409 conflict
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) get existing -> same spec (image and timeoutMs match)
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"registry.example/echo:1","timeoutMs":1000,
                         "requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"DEPLOYMENT",
                         "runtimeMode":"HTTP"}
                        """));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply",
                "-f", fn.toString()
        );

        assertThat(exit).isZero();

        // Only 2 requests: POST (409) + GET (same spec) — no DELETE or re-POST
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");

        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
    }

    @Test
    void applyDoesNotReplaceUnchangedManagedDeployment() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        java.nio.file.Files.writeString(fn, """
                name: echo
                image: registry.example/echo:1
                executionMode: DEPLOYMENT
                """);

        server.enqueue(new MockResponse().setResponseCode(409));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"registry.example/echo:1","command":[],"env":{},
                         "timeoutMs":30000,"concurrency":4,"queueSize":100,"maxRetries":3,
                         "endpointUrl":"http://echo.functions.svc",
                         "requestedExecutionMode":"DEPLOYMENT",
                         "effectiveExecutionMode":"EXTERNAL",
                         "deploymentBackend":"kubernetes","runtimeMode":"HTTP",
                         "scalingConfig":{"strategy":"INTERNAL","minReplicas":1,"maxReplicas":10,
                           "metrics":[{"type":"queue_depth","target":"5"}]}}
                        """));
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));

        CommandLine cli = new CommandLine(new RootCommand());

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply", "-f", fn.toString());

        assertThat(exit).isZero();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void applyOnConflictRetriesWhenGetReturnsNull() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        java.nio.file.Files.writeString(fn, """
                name: echo
                image: registry.example/echo:1
                """);

        // 1) register -> 409
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) GET -> 404 (null)
        server.enqueue(new MockResponse().setResponseCode(404));
        // 3) retry register -> 201
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());
        assertThat(exit).isZero();

        assertThat(server.getRequestCount()).isEqualTo(3);
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");
        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
        RecordedRequest r3 = server.takeRequest();
        assertThat(r3.getMethod()).isEqualTo("POST"); // retry
    }

    @Test
    void applyNon409ErrorExitsNonZero() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        java.nio.file.Files.writeString(fn, """
                name: echo
                image: registry.example/echo:1
                """);

        // register -> 500
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());
        assertThat(exit).isNotZero();
    }

    @Test
    void applyImageNotFoundShowsSpecificMessage() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        java.nio.file.Files.writeString(fn, """
                name: echo
                image: ghcr.io/example/does-not-exist:v1
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"IMAGE_NOT_FOUND\",\"message\":\"Image not found\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Image not found in registry");
    }

    @Test
    void applyImageAuthFailureShowsSpecificMessage() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        java.nio.file.Files.writeString(fn, """
                name: echo
                image: ghcr.io/example/private:v1
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(424)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"IMAGE_PULL_AUTH_REQUIRED\",\"message\":\"Authentication required\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Image pull authentication failed");
    }
}
