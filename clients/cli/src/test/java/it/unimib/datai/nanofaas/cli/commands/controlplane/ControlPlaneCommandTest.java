package it.unimib.datai.nanofaas.cli.commands.controlplane;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.testsupport.CliTestSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCommandTest {

    private static final String OPENAPI_WITH_METADATA = """
            openapi: 3.0.0
            paths:
              /v1/functions/{name}:enqueue:
                post:
                  summary: Enqueue an async invocation
                  responses:
                    '202':
                      description: Accepted
              /modules/build-metadata:
                get:
                  summary: Build metadata
            """;

    private static final String OPENAPI_WITHOUT_METADATA = """
            openapi: 3.0.0
            paths:
              /v1/functions/{name}:enqueue:
                post:
                  summary: Enqueue an async invocation
                  responses:
                    '202':
                      description: Accepted
            """;

    private static final String METADATA_JSON = """
            {"version":"0.20.0","revision":"abc123","dirty":false,
             "modules":["build-metadata","async-queue"],
             "build":{"type":"jvm","variant":null,"optimization":null,
                      "baseImages":{"builder":"b:1","runtime":"r:1"}},
             "runtime":{"architecture":"x86_64","kernelVersion":"6.8",
                        "javaVersion":"25","vm":"HotSpot","garbageCollectors":["G1"]}}
            """;

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
    void contractPrintsOpenApiBodyUnchanged() {
        String yaml = "openapi: 3.0.0\npaths: {}\n";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(yaml));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "control-plane", "contract");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo(yaml);
    }

    @Test
    void infoPrintsMetadataAndCapabilities() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(OPENAPI_WITH_METADATA));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(METADATA_JSON));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "control-plane", "info");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
                .contains("\"revision\":\"abc123\"")
                .contains("\"asyncInvocation\":true");
    }

    @Test
    void infoWithoutMetadataCapabilitySkipsMetadataRoute() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(OPENAPI_WITHOUT_METADATA));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "control-plane", "info");

        assertThat(result.exitCode()).isZero();
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("\"asyncInvocation\":true")
                .contains("\"metadata\":null");
    }

    @Test
    void infoMetadata404KeepsCapabilitiesAndNullMetadata() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(OPENAPI_WITH_METADATA));
        server.enqueue(new MockResponse().setResponseCode(404));

        CommandLine cli = new CommandLine(new RootCommand());
        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "control-plane", "info");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
                .contains("\"asyncInvocation\":true")
                .contains("\"buildMetadata\":true")
                .contains("\"metadata\":null");
    }
}
