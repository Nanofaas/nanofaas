package it.unimib.datai.nanofaas.modules.offload;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for review finding P2 ("L'offload perde gli header applicativi del
 * chiamante", docs/control-plane-review-2026-09-05.md), which the review verified only
 * statically ("Evidenza: statica"). It explicitly warns that a mock of the remote response
 * would not catch this: it requires a real traversal of two control planes, so this test
 * follows the two-instance pattern of {@link OffloadPressureE2eTest}.
 *
 * <p>Flow: a caller sends {@code x-tenant: acme} to the "edge" control plane. The function is
 * configured with an eager ({@code mode=always}) offload policy, so edge transparently proxies
 * to the "cloud" control plane instead of executing locally. Cloud's function is EXTERNAL and
 * points at a mock backend that echoes back whatever application headers it actually received.
 * If the offload hop preserved {@code x-tenant}, the echoed value survives end to end.
 *
 * <p>It does not: {@link DefaultOffloadGateway#invokeRemote} only forwards hop/tracing headers
 * as real HTTP headers (offload-hop marker, trace-id, traceparent, tracestate) — application
 * headers travel only inside the JSON body's {@code InvocationRequest.headers()} field. Cloud's
 * {@code InvocationController} always rebuilds {@code request.headers()} from the real HTTP
 * headers of that hop (see {@code withCallerHeaders}), discarding whatever the body carried. So
 * {@code x-tenant} is silently dropped.
 */
class OffloadHeaderLossE2eTest {

    private static final String FUNCTION = "header-echo";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockWebServer backend;
    private static ConfigurableApplicationContext cloud;
    private static ConfigurableApplicationContext edge;
    private static String cloudUrl;
    private static String edgeUrl;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void startInstances() throws IOException {
        backend = new MockWebServer();
        backend.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                Map<String, Object> parsed = MAPPER.readValue(request.getBody().readUtf8(), Map.class);
                Object headers = parsed.get("headers");
                String body = MAPPER.writeValueAsString(Map.of("receivedHeaders", headers == null ? Map.of() : headers));
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
        backend.start();

        cloud = new SpringApplicationBuilder(ControlPlaneApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.autoconfigure.exclude="
                        + "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.syncqueue."
                        + "SyncQueueRuntimeConfigAutoConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigConfiguration",
                "--sync-queue.enabled=false",
                "--nanofaas.registry.path=build/test-offload-header-cloud-functions.json");
        cloudUrl = "http://127.0.0.1:" + port(cloud, "local.server.port");

        edge = new SpringApplicationBuilder(ControlPlaneApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.autoconfigure.exclude="
                        + "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.syncqueue."
                        + "SyncQueueRuntimeConfigAutoConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigConfiguration",
                "--sync-queue.enabled=false",
                "--nanofaas.registry.path=build/test-offload-header-edge-functions.json");
        edgeUrl = "http://127.0.0.1:" + port(edge, "local.server.port");

        delete(cloudUrl, FUNCTION);
        delete(edgeUrl, FUNCTION);

        register(cloudUrl, """
                {"name": "%s", "image": "img", "executionMode": "EXTERNAL",
                 "endpointUrl": "%s", "timeoutMs": 10000}
                """.formatted(FUNCTION, backend.url("/invoke")));
        register(edgeUrl, """
                {"name": "%s", "image": "img", "executionMode": "LOCAL", "timeoutMs": 10000,
                 "offload": {"mode": "always", "targetUrl": "%s"}}
                """.formatted(FUNCTION, cloudUrl));
    }

    @AfterAll
    static void stopInstances() throws IOException {
        if (edge != null) {
            edge.close();
        }
        if (cloud != null) {
            cloud.close();
        }
        if (backend != null) {
            backend.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void applicationHeaderSurvivesAnEagerOffloadHop() throws Exception {
        Map<String, Object> receivedHeaders = invokeHeaderEcho(edgeUrl);

        // BUG (regression, fixed by A6): the offload hop used to drop application headers,
        // so the remote function never saw x-tenant even though the local caller sent it.
        // The second control plane rebuilds InvocationRequest.headers() from the HTTP
        // transport of the offload hop, so x-tenant must arrive as a real hop header.
        assertThat(receivedHeaders)
                .as("headers observed by the remote function after an eager offload hop")
                .containsEntry("x-tenant", "acme");
    }

    @Test
    @SuppressWarnings("unchecked")
    void applicationHeaderSurvivesDirectInvocationWithoutOffload() throws Exception {
        Map<String, Object> receivedHeaders = invokeHeaderEcho(cloudUrl);

        // Control for the offloaded case: invoking the same function on the cloud
        // instance directly (no offload) must also preserve the caller's x-tenant,
        // so a handler sees the same header value locally and offloaded.
        assertThat(receivedHeaders)
                .as("headers observed by the function on a direct (non-offloaded) call")
                .containsEntry("x-tenant", "acme");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeHeaderEcho(String baseUrl) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/functions/" + FUNCTION + ":invoke"))
                .header("Content-Type", "application/json")
                .header("x-tenant", "acme")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"ping\"}"))
                .build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
        assertThat(body.get("status")).isEqualTo("success");
        Object output = body.get("output");
        assertThat(output).as("function output envelope").isInstanceOf(Map.class);
        Object receivedHeaders = ((Map<String, Object>) output).get("receivedHeaders");
        assertThat(receivedHeaders)
                .as("headers observed by the handler")
                .isInstanceOf(Map.class);
        return (Map<String, Object>) receivedHeaders;
    }

    private static int port(ConfigurableApplicationContext context, String property) {
        String value = context.getEnvironment().getProperty(property);
        assertThat(value).as(property).isNotNull();
        return Integer.parseInt(value);
    }

    private static void delete(String baseUrl, String name) throws IOException {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/functions/" + name))
                .DELETE()
                .build());
    }

    private static void register(String baseUrl, String spec) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/functions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(spec))
                .build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }
}
