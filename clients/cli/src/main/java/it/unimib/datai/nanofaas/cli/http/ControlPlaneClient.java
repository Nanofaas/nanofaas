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

public final class ControlPlaneClient {
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
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/functions/" + name))
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
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/functions/" + name))
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

    public FunctionDetails registerFunction(FunctionSpec spec) {
        String body = json.toJson(spec);
        HttpRequest req = HttpRequest.newBuilder(base.resolve("v1/functions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 201) {
            throw httpError("register function", resp);
        }
        return json.fromJson(resp.body(), FunctionDetails.class);
    }

    public InvocationResponse invokeSync(String name, InvocationRequest request,
                                         String idempotencyKey, String traceId, Integer timeoutMs) {
        String body = json.toJson(request);
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve("v1/functions/" + name + ":invoke"))
                .header("Content-Type", "application/json")
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
        if (resp.statusCode() != 200) {
            throw httpError("invoke function", resp);
        }
        return json.fromJson(resp.body(), InvocationResponse.class);
    }

    public InvocationResponse enqueue(String name, InvocationRequest request, String idempotencyKey, String traceId) {
        String body = json.toJson(request);
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve("v1/functions/" + name + ":enqueue"))
                .header("Content-Type", "application/json")
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
