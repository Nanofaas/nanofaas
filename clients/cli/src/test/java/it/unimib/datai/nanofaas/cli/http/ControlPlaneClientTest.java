package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
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

        List<FunctionDetails> fns = client.listFunctions();
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

        FunctionDetails created = client.registerFunction(spec);
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
        InvocationResponse resp = client.invokeSync("echo", reqBody, null, null, null).response();

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

        FunctionDetails spec = client.getFunctionOrNull("echo");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo");
        assertThat(spec).isNotNull();
        assertThat(spec.name()).isEqualTo("echo");
    }

    @Test
    void getFunctionReadsRequestedExecutionModeFromResponseContract() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"name":"echo","image":"example/echo:1",
                         "requestedExecutionMode":"DEPLOYMENT","effectiveExecutionMode":"EXTERNAL"}
                        """));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionDetails function = client.getFunctionOrNull("echo");

        assertThat(function.requestedExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
        assertThat(function.effectiveExecutionMode()).isEqualTo(ExecutionMode.EXTERNAL);
    }

    @Test
    void getFunctionOrNullReturnsNullOn404() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionDetails spec = client.getFunctionOrNull("missing");
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
    void openApiReturnsBodyUnchanged() throws Exception {
        String yaml = "openapi: 3.0.0\npaths: {}\n";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody(yaml));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        String body = client.openApi();
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/openapi.yaml");
        assertThat(body).isEqualTo(yaml);
    }

    @Test
    void capabilitiesParsesOpenApiBooleans() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/yaml")
                .setBody("""
                        openapi: 3.0.0
                        paths:
                          /v1/functions/{name}:
                            patch:
                              summary: Update a function
                          /v1/functions/{name}/replicas:
                            get:
                              summary: Read replicas
                            put:
                              summary: Set replicas
                          /v1/functions/{name}:enqueue:
                            post:
                              summary: Enqueue
                              responses:
                                '202':
                                  description: Accepted
                          /modules/build-metadata:
                            get:
                              summary: Build metadata
                          /v1/admin/runtime-config:
                            get:
                              summary: Runtime config
                          /v1/admin/runtime-config/{namespace}:
                            patch:
                              summary: Update runtime config
                          /v1/admin/runtime-config/{namespace}/validate:
                            post:
                              summary: Validate runtime config
                        """));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        ControlPlaneCapabilities caps = client.capabilities();

        assertThat(caps.functionUpdate()).isTrue();
        assertThat(caps.replicas()).isTrue();
        assertThat(caps.asyncInvocation()).isTrue();
        assertThat(caps.buildMetadata()).isTrue();
        assertThat(caps.runtimeConfig()).isTrue();
    }

    @Test
    void openApiNon200ThrowsControlPlaneHttpException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(client::openApi)
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(500));
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

        assertThatThrownBy(client::listFunctions)
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> {
                    ControlPlaneHttpException he = (ControlPlaneHttpException) ex;
                    assertThat(he.getStatus()).isEqualTo(500);
                    assertThat(he.getBody()).isEqualTo("internal error");
                });
    }

    @Test
    void deleteFunctionServerErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.deleteFunction("echo"))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(500));
    }

    @Test
    void getExecutionErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.getExecution("exec-missing"))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(404));
    }

    @Test
    void invokeSyncErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
        InvocationRequest req = new InvocationRequest(Map.of("x", 1), null);

        assertThatThrownBy(() -> client.invokeSync("echo", req, null, null, null))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(503));
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
                    assertThat(he.getStatus()).isEqualTo(500);
                });
    }

    @Test
    void invokeReturnsFunctionDecidedNon200() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader("X-NanoFaaS-Function-Status", "true")
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"executionId":"exec-1","status":"success",
                         "output":{"error":"missing"},"statusCode":404}
                        """));

        InvocationCallResult result = new ControlPlaneClient(server.url("/").toString())
                .invokeSync("lookup", new InvocationRequest(Map.of(), null), null, null, null);

        assertThat(result.httpStatus()).isEqualTo(404);
        assertThat(result.response().statusCode()).isEqualTo(404);
    }

    @Test
    void invokeReturnsFunctionDecided201() {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("X-NanoFaaS-Function-Status", "true")
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"executionId":"exec-1","status":"success",
                         "output":{"id":"created"},"statusCode":201}
                        """));

        InvocationCallResult result = new ControlPlaneClient(server.url("/").toString())
                .invokeSync("create", new InvocationRequest(Map.of(), null), null, null, null);

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.response().statusCode()).isEqualTo(201);
    }

    @Test
    void invokeUnmarked404StillThrows() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("not found"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        InvocationRequest request = new InvocationRequest(Map.of(), null);
        assertThatThrownBy(() -> client.invokeSync("missing", request, null, null, null))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(404));
    }

    @Test
    void invokeReturnsSyntheticResponseFor204() {
        server.enqueue(new MockResponse()
                .setResponseCode(204)
                .addHeader("X-NanoFaaS-Function-Status", "true")
                .addHeader("X-Execution-Id", "exec-204"));

        InvocationCallResult result = new ControlPlaneClient(server.url("/").toString())
                .invokeSync("noop", new InvocationRequest(Map.of(), null), null, null, null);

        assertThat(result.httpStatus()).isEqualTo(204);
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.response().statusCode()).isEqualTo(204);
        assertThat(result.response().output()).isNull();
        assertThat(result.response().executionId()).isEqualTo("exec-204");
    }

    @Test
    void updateFunctionPatchesWithOnlyNonNullFields() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"example/echo:1\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionDetails updated = client.updateFunction("echo", new FunctionPatch(3, null, null, null));
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("PATCH");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo");
        assertThat(req.getHeader("Content-Type")).contains("application/json");

        // Exactly the patched field, and nothing else: an extra key (Jackson used to
        // serialize the isEmpty() accessor as "empty") is rejected by the control
        // plane as an unreadable body, which failed every fn update with HTTP 400.
        String body = req.getBody().readUtf8();
        assertThat(body).isEqualTo("{\"concurrency\":3}");
        assertThat(updated.name()).isEqualTo("echo");
    }

    @Test
    void updateFunctionNon200Throws() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        FunctionPatch patch = new FunctionPatch(null, null, null, null);
        assertThatThrownBy(() -> client.updateFunction("echo", patch))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(500));
    }

    @Test
    void getReplicasParsesStatusResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"desiredReplicas\":3,\"readyReplicas\":2}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        ReplicaStatus status = client.getReplicas("echo");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo/replicas");
        assertThat(status.name()).isEqualTo("echo");
        assertThat(status.desiredReplicas()).isEqualTo(3);
        assertThat(status.readyReplicas()).isEqualTo(2);
    }

    @Test
    void setReplicasPutsBodyToExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"function\":\"echo\",\"replicas\":3}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        client.setReplicas("echo", 3);
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("PUT");
        assertThat(req.getPath()).isEqualTo("/v1/functions/echo/replicas");
        assertThat(req.getHeader("Content-Type")).contains("application/json");
        assertThat(req.getBody().readUtf8()).isEqualTo("{\"replicas\":3}");
    }

    @Test
    void setReplicasAllowsZero() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"function\":\"echo\",\"replicas\":0}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        client.setReplicas("echo", 0);
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("PUT");
        assertThat(req.getBody().readUtf8()).isEqualTo("{\"replicas\":0}");
    }

    @Test
    void getReplicasPreserves400() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("unmanaged"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.getReplicas("echo"))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(400));
    }

    @Test
    void setReplicasPreserves503() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("backend down"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(() -> client.setReplicas("echo", 2))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).getStatus()).isEqualTo(503));
    }

    @Test
    void getRuntimeConfigReturnsAggregateSnapshot() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"revision\":7,\"namespaces\":{\"requests\":{\"maxQueueWait\":\"PT2S\"}}}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        RuntimeConfigSnapshot snapshot = client.getRuntimeConfig();
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/admin/runtime-config");
        assertThat(snapshot.revision()).isEqualTo(7);
        assertThat(snapshot.namespaces()).containsKey("requests");
        assertThat(snapshot.namespaces().get("requests")).containsEntry("maxQueueWait", "PT2S");
    }

    @Test
    void getRuntimeConfigNamespaceReturnsUntypedJsonNode() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"maxQueueWait\":\"PT2S\"}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        JsonNode node = client.getRuntimeConfig("requests");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v1/admin/runtime-config/requests");
        assertThat(node.path("maxQueueWait").asText()).isEqualTo("PT2S");
    }

    @Test
    void validateRuntimeConfigPostsValuesToExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"valid\":true}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        JsonNode node = client.validateRuntimeConfig("requests", Map.of("maxQueueWait", "PT2S"));
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/admin/runtime-config/requests/validate");
        assertThat(req.getHeader("Content-Type")).contains("application/json");
        assertThat(req.getBody().readUtf8()).isEqualTo("{\"maxQueueWait\":\"PT2S\"}");
        assertThat(node.path("valid").asBoolean()).isTrue();
    }

    @Test
    void patchRuntimeConfigPatchesExpectedBody() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {"revision":8,"effectiveConfig":{"revision":8,"namespaces":{"requests":{"maxQueueWait":"PT3S"}}},
                         "appliedAt":"2026-01-01T00:00:00Z","changeId":"abc","warnings":[]}
                        """));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        RuntimeConfigPatchResponse response = client.patchRuntimeConfig("requests",
                new RuntimeConfigPatchRequest(7, Map.of("maxQueueWait", "PT3S")));
        RecordedRequest req = server.takeRequest();

        assertThat(req.getMethod()).isEqualTo("PATCH");
        assertThat(req.getPath()).isEqualTo("/v1/admin/runtime-config/requests");
        assertThat(req.getHeader("Content-Type")).contains("application/json");
        assertThat(req.getBody().readUtf8()).isEqualTo("{\"expectedRevision\":7,\"values\":{\"maxQueueWait\":\"PT3S\"}}");
        assertThat(response.revision()).isEqualTo(8);
        assertThat(response.effectiveConfig().namespaces()).containsKey("requests");
        assertThat(response.changeId()).isEqualTo("abc");
    }

    @Test
    void getRuntimeConfigNon200ThrowsWithBodyPreserved() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("admin disabled"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        assertThatThrownBy(client::getRuntimeConfig)
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> {
                    ControlPlaneHttpException he = (ControlPlaneHttpException) ex;
                    assertThat(he.getStatus()).isEqualTo(404);
                    assertThat(he.getBody()).isEqualTo("admin disabled");
                });
    }

    @Test
    void functionNameWithReservedCharactersIsPercentEncoded() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
        client.getFunctionOrNull("a b#c?d");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getPath()).isEqualTo("/v1/functions/a%20b%23c%3Fd");
    }

    @Test
    void functionNameWithSlashIsEncodedNotSplit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
        client.getFunctionOrNull("a/b");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getPath()).isEqualTo("/v1/functions/a%2Fb");
    }

    @Test
    void runtimeConfigNamespaceIsPercentEncoded() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{}"));

        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
        client.getRuntimeConfig("con#trol");
        RecordedRequest req = server.takeRequest();

        assertThat(req.getPath()).isEqualTo("/v1/admin/runtime-config/con%23trol");
    }

    @Test
    void validateRuntimeConfig422ThrowsWithBodyPreserved() {
        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[\"maxQueueWait must be a duration\"]}"));
        ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

        Map<String, Object> values = Map.of("maxQueueWait", "bad");
        assertThatThrownBy(() -> client.validateRuntimeConfig("requests", values))
                .isInstanceOf(ControlPlaneHttpException.class)
                .satisfies(ex -> {
                    ControlPlaneHttpException he = (ControlPlaneHttpException) ex;
                    assertThat(he.getStatus()).isEqualTo(422);
                    assertThat(he.getBody()).contains("maxQueueWait must be a duration");
                });
    }
}
