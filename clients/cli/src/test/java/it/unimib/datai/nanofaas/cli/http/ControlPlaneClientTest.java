package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void getFunctionOrNullReturnsSpecWhenFound() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"example/echo:1\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionSpec spec = client.getFunctionOrNull("echo");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo");
        assertThat(spec).isNotNull();
        assertThat(spec.name()).isEqualTo("echo");
    }

    @Test
    void getFunctionOrNullReturnsNullOn404() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionSpec spec = client.getFunctionOrNull("missing");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getPath()).isEqualTo("/v1/functions/missing");
        assertThat(spec).isNull();
    }

    @Test
    void deleteFunctionSendsDeleteTo204() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        client.deleteFunction("echo");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("DELETE");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo");
    }

    @Test
    void deleteFunctionSilentOn404() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        client.deleteFunction("missing");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("DELETE");
        assertThat(req.getPath()).isEqualTo("/v1/functions/missing");
    }

    @Test
    void enqueuePostsToExpectedPathWith202() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-2\",\"status\":\"queued\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        InvocationRequest reqBody = new InvocationRequest(Map.of("key", "val"), null);
        InvocationResponse resp = client.enqueue("echo", reqBody, null, null);
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo:enqueue");
        assertThat(resp.executionId()).isEqualTo("exec-2");
    }

    @Test
    void getExecutionUsesExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-1\",\"status\":\"success\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        ExecutionStatus status = client.getExecution("exec-1");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/executions/exec-1");
        assertThat(status.executionId()).isEqualTo("exec-1");
        assertThat(status.status()).isEqualTo("success");
    }

    @Test
    void invokeSyncSendsOptionalHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-1\",\"status\":\"ok\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        InvocationRequest reqBody = new InvocationRequest(Map.of("x", 1), null);
        client.invokeSync("echo", reqBody, "idem-123", "trace-456", 5000);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("idem-123");
        assertThat(req.getHeader("X-Trace-Id")).isEqualTo("trace-456");
        assertThat(req.getHeader("X-Timeout-Ms")).isEqualTo("5000");
    }

    @Test
    void non2xxResponseThrowsControlPlaneHttpException() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("internal error"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.listFunctions())
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> {
                    ControlPlaneHttpException he = (ControlPlaneHttpException) ex;
                    assertThat(he.status()).isEqualTo(500);
                    assertThat(he.body()).isEqualTo("internal error");
                });
    }

    @Test
    void deleteFunctionServerErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.deleteFunction("echo"))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).status()).isEqualTo(500));
    }

    @Test
    void getExecutionErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.getExecution("exec-missing"))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).status()).isEqualTo(404));
    }

    @Test
    void invokeSyncErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
        InvocationRequest req = new InvocationRequest(Map.of("x", 1), null);

        assertThatThrownBy(() -> client.invokeSync("echo", req, null, null, null))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).status()).isEqualTo(503));
    }

    @Test
    void enqueueNon202ThrowsControlPlaneHttpException() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("server error"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
        InvocationRequest reqBody = new InvocationRequest(Map.of("x", 1), null);

        assertThatThrownBy(() -> client.enqueue("echo", reqBody, null, null))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> {
                    ControlPlaneHttpException he = (ControlPlaneHttpException) ex;
                    assertThat(he.status()).isEqualTo(500);
                });
    }
}
