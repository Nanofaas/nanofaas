package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneClientTest {

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
    void listFunctionsUsesExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("[{\"name\":\"echo\",\"image\":\"example/echo:1\"}]"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        List<FunctionSpec> fns = client.listFunctions();
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/functions");
        assertThat(fns).hasSize(1);
        assertThat(fns.getFirst().name()).isEqualTo("echo");
    }

    @Test
    void registerFunctionPostsJsonToExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"example/echo:1\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "example/echo:1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        FunctionSpec created = client.registerFunction(spec);
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions");
        assertThat(req.getHeader("Content-Type")).contains("application/json");
        assertThat(req.getBody().readUtf8()).contains("\"name\":\"echo\"");
        assertThat(created.name()).isEqualTo("echo");
    }

    @Test
    void invokeSyncPostsToExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Execution-Id", "exec-1")
                .setBody("{\"executionId\":\"exec-1\",\"status\":\"ok\",\"output\":{\"message\":\"hi\"}}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        InvocationRequest reqBody = new InvocationRequest(Map.of("message", "hi"), null);
        InvocationResponse resp = client.invokeSync("echo", reqBody, null, null, null);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo:invoke");
        assertThat(resp.executionId()).isEqualTo("exec-1");
    }
}
