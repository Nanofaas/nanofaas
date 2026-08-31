package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.core.type.TypeReference;
import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ControlPlaneClient {
    private static final String FUNCTIONS_PATH = "v1/functions/";
    private static final String APPLICATION_JSON = "application/json";
    private static final String CONTENT_TYPE = "Content-Type";

    private final URI base;
    private final HttpClient http;
    private final HttpJson json;

    public ControlPlaneClient(String baseUrl) {
        this(normalizeBase(baseUrl), HttpClient.newHttpClient(), new HttpJson());
    }

    ControlPlaneClient(URI base, HttpClient http, HttpJson json) {
        this.base = base;
        this.http = http;
        this.json = json;
    }

    public List<FunctionDetails> listFunctions() {
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/functions"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw httpError("list functions", resp);
        }

        try {
            return json.mapper().readValue(resp.body(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse function list", e);
        }
    }

    public FunctionDetails getFunctionOrNull(String name) {
        HttpRequest req = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw httpError("get function", resp);
        }
        return json.fromJson(resp.body(), FunctionDetails.class);
    }

    public void deleteFunction(String name) {
        HttpRequest req = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name))
                .DELETE()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 404) {
            return;
        }
        if (resp.statusCode() != 204) {
            throw httpError("delete function", resp);
        }
    }

    public FunctionDetails updateFunction(String name, FunctionPatch patch) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name))
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json.toJson(patch)))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw httpError("update function", response);
        }
        return json.fromJson(response.body(), FunctionDetails.class);
    }

    public ReplicaStatus getReplicas(String name) {
        HttpRequest req = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name + "/replicas"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw httpError("get replicas", resp);
        }
        return json.fromJson(resp.body(), ReplicaStatus.class);
    }

    public Map<String, Object> setReplicas(String name, int replicas) {
        String body = json.toJson(Map.of("replicas", replicas));
        HttpRequest req = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name + "/replicas"))
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw httpError("set replicas", resp);
        }
        try {
            return json.mapper().readValue(resp.body(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse replica response", e);
        }
    }

    public FunctionDetails registerFunction(FunctionSpec spec) {
        String body = json.toJson(spec);
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/functions"))
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 201) {
            throw httpError("register function", resp);
        }
        return json.fromJson(resp.body(), FunctionDetails.class);
    }

    public InvocationCallResult invokeSync(String name, InvocationRequest request,
                                           String idempotencyKey, String traceId, Integer timeoutMs) {
        String body = json.toJson(request);
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name + ":invoke"))
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(300));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (traceId != null && !traceId.isBlank()) {
            b.header("X-Trace-Id", traceId);
        }
        if (timeoutMs != null) {
            b.header("X-Timeout-Ms", String.valueOf(timeoutMs));
        }

        HttpResponse<String> resp = send(b.build());
        boolean functionDecided = resp.headers().firstValue("X-NanoFaaS-Function-Status")
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (resp.statusCode() != 200 && !functionDecided) {
            throw httpError("invoke function", resp);
        }
        InvocationResponse response = resp.body() == null || resp.body().isBlank()
                ? new InvocationResponse(
                        resp.headers().firstValue("X-Execution-Id").orElse(null),
                        resp.statusCode() >= 200 && resp.statusCode() < 300 ? "success" : "error",
                        null, null, resp.statusCode(), null, null)
                : json.fromJson(resp.body(), InvocationResponse.class);
        return new InvocationCallResult(resp.statusCode(), response);
    }

    public InvocationResponse enqueue(String name, InvocationRequest request, String idempotencyKey, String traceId) {
        String body = json.toJson(request);
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name + ":enqueue"))
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (traceId != null && !traceId.isBlank()) {
            b.header("X-Trace-Id", traceId);
        }

        HttpResponse<String> resp = send(b.build());
        if (resp.statusCode() != 202) {
            throw httpError("enqueue function", resp);
        }
        return json.fromJson(resp.body(), InvocationResponse.class);
    }

    public ExecutionStatus getExecution(String executionId) {
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/executions/" + executionId))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw httpError("get execution", resp);
        }
        return json.fromJson(resp.body(), ExecutionStatus.class);
    }

    /**
     * Fetches the control-plane's OpenAPI contract document.
     *
     * @return the raw OpenAPI document body, unchanged
     * @throws ControlPlaneHttpException if the document is not served with a 200 status
     */
    public String openApi() {
        HttpRequest req = HttpRequest.newBuilder(base.resolve("openapi.yaml"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw httpError("get openapi", resp);
        }
        return resp.body();
    }

    /**
     * Fetches the control-plane's build metadata, if the route is served.
     *
     * @return the parsed build metadata, or {@code null} when the control-plane
     *         responds with 404 (metadata module not loaded)
     * @throws ControlPlaneHttpException for any non-200/404 status
     */
    public BuildMetadata buildMetadataOrNull() {
        HttpRequest req = HttpRequest.newBuilder(base.resolve("modules/build-metadata"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw httpError("get build metadata", resp);
        }
        return json.fromJson(resp.body(), BuildMetadata.class);
    }

    /**
     * Derives the control-plane's capabilities from its OpenAPI contract.
     *
     * <p>This performs a fresh fetch of {@code /openapi.yaml} on every call; there is
     * deliberately no runtime cache.</p>
     *
     * @return the parsed capabilities
     */
    public ControlPlaneCapabilities capabilities() {
        return ControlPlaneCapabilities.fromOpenApi(openApi());
    }

    /**
     * Sends an HTTP request to the control-plane and handles transport errors.
     *
     * @param request HTTP request to execute
     * @return HTTP response with body as string
     * @throws ControlPlaneHttpException if the request fails
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            // Execute HTTP request and return response body as string.
            return http.send(request, HttpResponse.BodyHandlers.ofString());

        } catch (IOException e) {
            // Covers connection errors, DNS failures, and server unreachable cases.
            throw new ControlPlaneHttpException(0,
                    String.format("Failed to call control-plane (%s %s): %s", request.method(), request.uri(), e),
                    e);

        } catch (InterruptedException e) {
            // Restore interrupt flag before propagating the error.
            Thread.currentThread().interrupt();

            throw new ControlPlaneHttpException(0,
                    String.format("Interrupted calling control-plane: %s %s", request.method(), request.uri()),
                    e);
        }
    }

    /**
     * Creates an exception for an unsuccessful HTTP response.
     *
     * @param action operation being performed
     * @param resp HTTP response containing the error details
     * @return control-plane HTTP exception
     */
    private static ControlPlaneHttpException httpError(String action, HttpResponse<String> resp) {
        // Include status code and endpoint to make HTTP failures easier to debug.
        String msg = String.format("Control-plane HTTP %d during %s (%s)", resp.statusCode(), action, resp.request().uri());

        // Preserve server response body, which may contain additional error details.
        return new ControlPlaneHttpException(resp.statusCode(), msg, resp.body());
    }

    private static URI normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Missing control-plane endpoint");
        }
        String u = baseUrl.endsWith("/") ? baseUrl : (baseUrl + "/");
        return URI.create(u);
    }
}
