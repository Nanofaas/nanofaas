package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.testsupport.CliTestSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

class FnReplicasCommandTest {

    private static final String OPENAPI_WITH_REPLICAS = """
            openapi: 3.0.0
            paths:
              /v1/functions/{name}/replicas:
                get:
                  summary: Get replicas
                put:
                  summary: Set replicas
            """;

    private static final String OPENAPI_WITHOUT_REPLICAS = """
            openapi: 3.0.0
            paths: {}
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

    private MockResponse openApiResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(body);
    }

    @Test
    void getReplicasPrintsStatusJson() {
        server.enqueue(openApiResponse(OPENAPI_WITH_REPLICAS));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"desiredReplicas\":3,\"readyReplicas\":2}"));

        CommandLine cli = new CommandLine(new RootCommand());

        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "fn", "replicas", "get", "echo");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().trim())
                .startsWith("{")
                .contains("\"name\":\"echo\"")
                .contains("\"desiredReplicas\":3")
                .contains("\"readyReplicas\":2");
    }

    @Test
    void setReplicasPutsBody() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITH_REPLICAS));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"function\":\"echo\",\"replicas\":3}"));

        CommandLine cli = new CommandLine(new RootCommand());

        CliTestSupport.CommandResult result = CliTestSupport.executeAndCaptureStdout(
                cli, "--endpoint", server.url("/").toString(), "fn", "replicas", "set", "echo", "3");

        assertThat(result.exitCode()).isZero();
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest probe = server.takeRequest();
        assertThat(probe.getPath()).isEqualTo("/openapi.yaml");
        RecordedRequest put = server.takeRequest();
        assertThat(put.getMethod()).isEqualTo("PUT");
        assertThat(put.getPath()).isEqualTo("/v1/functions/echo/replicas");
        assertThat(put.getBody().readUtf8()).isEqualTo("{\"replicas\":3}");
    }

    @Test
    void setZeroReplicasIsAllowed() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITH_REPLICAS));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"function\":\"echo\",\"replicas\":0}"));

        CommandLine cli = new CommandLine(new RootCommand());

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(), "fn", "replicas", "set", "echo", "0");

        assertThat(exit).isZero();
        server.takeRequest(); // capability probe
        RecordedRequest put = server.takeRequest();
        assertThat(put.getBody().readUtf8()).isEqualTo("{\"replicas\":0}");
    }

    @Test
    void setNegativeReplicasFailsLocallyWithoutHttp() {
        CommandLine cli = new CommandLine(new RootCommand());

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(), "fn", "replicas", "set", "echo", "-1");

        assertThat(exit).isNotZero();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void replicasWhenUnsupportedMakesOnlyContractRequestAndReportsError() throws Exception {
        server.enqueue(openApiResponse(OPENAPI_WITHOUT_REPLICAS));

        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        cli.setErr(new PrintWriter(err, true));

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(), "fn", "replicas", "get", "echo");

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Replica management is not supported by this control-plane build");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(server.takeRequest().getPath()).isEqualTo("/openapi.yaml");
    }
}
