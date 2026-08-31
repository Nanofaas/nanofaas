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

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FnUpdateCommandTest {

    private static final String OPENAPI = """
            openapi: 3.0.0
            info:
              title: nanoFaaS Control Plane
              version: 1.0.0
            paths:
              /v1/functions/{name}:
                patch:
                  summary: Update a function
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

    @Test
    void updatePatchesFunction() throws Exception {
        Path patch = tmp.resolve("patch.yaml");
        java.nio.file.Files.writeString(patch, """
                timeoutMs: 7000
                """);

        // 1) GET /openapi.yaml -> capabilities
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(OPENAPI));
        // 2) PATCH -> 200
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\",\"timeoutMs\":7000}"));

        CommandLine cli = new CommandLine(new RootCommand());

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "update", "echo", "-f", patch.toString());
        assertThat(exit).isZero();

        assertThat(server.getRequestCount()).isEqualTo(2);
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("GET");
        assertThat(r1.getPath()).isEqualTo("/openapi.yaml");
        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("PATCH");
        assertThat(r2.getPath()).isEqualTo("/v1/functions/echo");
        assertThat(r2.getBody().readUtf8()).contains("\"timeoutMs\":7000");
    }

    @Test
    void updateRejectsEmptyPatchLocally() throws Exception {
        Path patch = tmp.resolve("patch.yaml");
        java.nio.file.Files.writeString(patch, "{}");

        // Only the capability probe; no PATCH issued for an empty patch.
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(OPENAPI));

        CommandLine cli = new CommandLine(new RootCommand());

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "fn", "update", "echo", "-f", patch.toString());
        assertThat(exit).isNotZero();

        assertThat(server.getRequestCount()).isEqualTo(1);
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("GET");
        assertThat(r1.getPath()).isEqualTo("/openapi.yaml");
    }
}
