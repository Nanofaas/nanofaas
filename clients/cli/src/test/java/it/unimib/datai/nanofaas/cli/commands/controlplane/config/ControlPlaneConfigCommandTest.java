package it.unimib.datai.nanofaas.cli.commands.controlplane.config;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.testsupport.CliTestSupport;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneConfigCommandTest {

    private static final String OPENAPI_WITH_RUNTIME_CONFIG = """
            openapi: 3.0.0
            paths:
              /v1/admin/runtime-config:
                get:
                  summary: Get runtime config
              /v1/admin/runtime-config/{namespace}:
                patch:
                  summary: Patch runtime config
              /v1/admin/runtime-config/{namespace}/validate:
                post:
                  summary: Validate runtime config
            """;

    private static final String OPENAPI_WITHOUT_RUNTIME_CONFIG = """
            openapi: 3.0.0
            paths: {}
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

    // --- RuntimeConfigInput ---

    @Test
    void inputPlainValuesMapYieldsNullRevisionAndValues() throws Exception {
        Path p = tmp.resolve("plain.yaml");
        Files.writeString(p, "maxQueueWait: PT2S\n");

        RuntimeConfigInput input = RuntimeConfigInput.load(p);

        assertThat(input.expectedRevision()).isNull();
        assertThat(input.values()).containsEntry("maxQueueWait", "PT2S");
    }

    @Test
    void inputEnvelopeYieldsRevisionAndValues() throws Exception {
        Path p = tmp.resolve("envelope.yaml");
        Files.writeString(p, """
                expectedRevision: 7
                values:
                  maxQueueWait: PT2S
                """);

        RuntimeConfigInput input = RuntimeConfigInput.load(p);

        assertThat(input.expectedRevision()).isEqualTo(7L);
        assertThat(input.values()).hasSize(1).containsEntry("maxQueueWait", "PT2S");
    }

    @Test
    void inputNonObjectYamlIsRejected() throws Exception {
        Path p = tmp.resolve("scalar.yaml");
        Files.writeString(p, "just a string\n");

        assertThatThrownBy(() -> RuntimeConfigInput.load(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputNonObjectValuesIsRejected() throws Exception {
        Path p = tmp.resolve("bad.yaml");
        Files.writeString(p, "values: not-an-object\n");

        assertThatThrownBy(() -> RuntimeConfigInput.load(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- get ---

    @Test
    void getPrintsAggregateSnapshotJson() {
        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"revision\":7,\"namespaces\":{}}"));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "control-plane", "config", "get");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("\"revision\":7");
    }

    @Test
    void getNamespacePrintsNamespaceJson() {
        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"maxQueueWait\":\"PT2S\"}"));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "control-plane", "config", "get", "requests");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("\"maxQueueWait\":\"PT2S\"");
    }

    @Test
    void get404ReportsDisabledWithEnablementFlag() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("--endpoint", server.url("/").toString(),
                "control-plane", "config", "get", "requests");

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("nanofaas.admin.runtime-config.enabled=true");
    }

    // --- validate ---

    @Test
    void validatePrintsResultJson() throws Exception {
        Path p = tmp.resolve("values.yaml");
        Files.writeString(p, "maxQueueWait: PT2S\n");

        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"valid\":true}"));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(),
                "control-plane", "config", "validate", "requests", "-f", p.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("\"valid\":true");
    }

    @Test
    void validate422PrintsErrors() throws Exception {
        Path p = tmp.resolve("values.yaml");
        Files.writeString(p, "maxQueueWait: PT2S\n");

        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[\"maxQueueWait must be a duration\"]}"));

        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("--endpoint", server.url("/").toString(),
                "control-plane", "config", "validate", "requests", "-f", p.toString());

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("maxQueueWait must be a duration");
    }

    // --- patch ---

    @Test
    void patchUsesSuppliedRevision() throws Exception {
        Path p = tmp.resolve("patch.yaml");
        Files.writeString(p, """
                expectedRevision: 7
                values:
                  maxQueueWait: PT3S
                """);

        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"revision":8,"effectiveConfig":{"revision":8,"namespaces":{}},
                         "appliedAt":"2026-01-01T00:00:00Z","changeId":"c1","warnings":[]}
                        """));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(),
                "control-plane", "config", "patch", "requests", "-f", p.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("\"revision\":8");

        assertThat(server.getRequestCount()).isEqualTo(2);
        server.takeRequest(); // capability probe
        RecordedRequest patch = server.takeRequest();
        assertThat(patch.getMethod()).isEqualTo("PATCH");
        assertThat(patch.getPath()).isEqualTo("/v1/admin/runtime-config/requests");
        assertThat(patch.getBody().readUtf8())
                .contains("\"expectedRevision\":7")
                .contains("\"maxQueueWait\":\"PT3S\"");
    }

    @Test
    void patchFetchesRevisionWhenAbsent() throws Exception {
        Path p = tmp.resolve("patch.yaml");
        Files.writeString(p, "maxQueueWait: PT3S\n");

        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"revision\":7,\"namespaces\":{}}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"revision":8,"effectiveConfig":{"revision":8,"namespaces":{}},
                         "appliedAt":"2026-01-01T00:00:00Z","changeId":"c1","warnings":[]}
                        """));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(),
                "control-plane", "config", "patch", "requests", "-f", p.toString());

        assertThat(result.exitCode()).isZero();

        assertThat(server.getRequestCount()).isEqualTo(3);
        server.takeRequest(); // capability probe
        RecordedRequest get = server.takeRequest();
        assertThat(get.getPath()).isEqualTo("/v1/admin/runtime-config");
        RecordedRequest patch = server.takeRequest();
        assertThat(patch.getBody().readUtf8()).contains("\"expectedRevision\":7");
    }

    @Test
    void patch409SurfacesCurrentRevision() throws Exception {
        Path p = tmp.resolve("patch.yaml");
        Files.writeString(p, """
                expectedRevision: 6
                values:
                  maxQueueWait: PT3S
                """);

        server.enqueue(openApiResponse(OPENAPI_WITH_RUNTIME_CONFIG));
        server.enqueue(new MockResponse()
                .setResponseCode(409)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"revision mismatch\",\"currentRevision\":7}"));

        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("--endpoint", server.url("/").toString(),
                "control-plane", "config", "patch", "requests", "-f", p.toString());

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("current revision is 7");
    }

    // --- capability ---

    @Test
    void configWhenUnsupportedReportsNotSupportedByThisBuild() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITHOUT_RUNTIME_CONFIG));

        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute("--endpoint", server.url("/").toString(),
                "control-plane", "config", "get");

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Runtime configuration is not supported by this control-plane build");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
