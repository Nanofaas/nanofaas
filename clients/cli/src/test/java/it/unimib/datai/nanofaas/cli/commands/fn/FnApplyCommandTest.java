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
        assertThat(exit).isEqualTo(0);

        // Only 1 request: POST (201)
        assertThat(server.getRequestCount()).isEqualTo(1);
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions");
    }

    @Test
    void applyOnConflictReplacesWhenDifferent() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        String yaml = """
                name: echo
                image: registry.example/echo:1
                timeoutMs: 1000
                x-cli:
                  build:
                    context: .
                """;
        java.nio.file.Files.writeString(fn, yaml);

        // 1) register -> 409 conflict
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) get existing -> different spec
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:OLD\",\"timeoutMs\":1000}"));
        // 3) delete -> 204
        server.enqueue(new MockResponse().setResponseCode(204));
        // 4) register -> 201 created
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\",\"timeoutMs\":1000}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply",
                "-f", fn.toString()
        );

        assertThat(exit).isEqualTo(0);

        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");
        assertThat(r1.getPath()).isEqualTo("/v1/functions");

        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
        assertThat(r2.getPath()).isEqualTo("/v1/functions/echo");

        RecordedRequest r3 = server.takeRequest();
        assertThat(r3.getMethod()).isEqualTo("DELETE");
        assertThat(r3.getPath()).isEqualTo("/v1/functions/echo");

        RecordedRequest r4 = server.takeRequest();
        assertThat(r4.getMethod()).isEqualTo("POST");
        assertThat(r4.getPath()).isEqualTo("/v1/functions");
        assertThat(r4.getBody().readUtf8()).contains("\"image\":\"registry.example/echo:1\"");
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
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\",\"timeoutMs\":1000}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "apply",
                "-f", fn.toString()
        );

        assertThat(exit).isEqualTo(0);

        // Only 2 requests: POST (409) + GET (same spec) — no DELETE or re-POST
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");

        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
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
        assertThat(exit).isEqualTo(0);

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
        assertThat(exit).isNotEqualTo(0);
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

        assertThat(exit).isNotEqualTo(0);
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

        assertThat(exit).isNotEqualTo(0);
        assertThat(err.toString()).contains("Image pull authentication failed");
    }
}
