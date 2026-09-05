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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for the headers {@link DefaultOffloadGateway} keeps under dedicated
 * control while forwarding the caller's application headers (A6): the offload-hop marker
 * (single-hop / re-offload prevention), the trace headers, and — on the receiving side —
 * the reconstruction of {@code InvocationRequest.headers()} from the real HTTP headers of
 * the offload hop.
 *
 * <p>Topology: an "edge" instance eagerly offloads to a "cloud" instance. Cloud's function
 * is EXTERNAL with its real backend at {@link #hopBackend} (which echoes the application
 * headers it receives in the {@code InvocationRequest} body), but the same function ALSO
 * declares an eager offload policy pointing at {@link #rogue}. So:
 * <ul>
 *   <li>a direct call to cloud proves the policy is live — cloud offloads it to rogue;</li>
 *   <li>a call to edge that reaches cloud carrying {@code X-NanoFaaS-Offload-Hop} must
 *       NOT be re-offloaded to rogue, and must instead be executed by cloud against
 *       hopBackend — that is the re-offload-prevention assertion;</li>
 *   <li>the hopBackend echo is the reconstruction assertion on the second control plane
 *       (the handler sees the {@code x-tenant} the local caller sent), and the recorded
 *       request's {@code X-Trace-Id} is the tracing-preservation assertion.</li>
 * </ul>
 *
 * <p>Both instances run with the async and sync queue modules excluded so the cloud-side
 * execution is dispatched inline (deterministic, no scheduler batching).
 */
class OffloadHopGuardE2eTest {

    private static final String FUNCTION = "hop-guard";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockWebServer hopBackend;
    private static MockWebServer rogue;
    private static ConfigurableApplicationContext cloud;
    private static ConfigurableApplicationContext edge;
    private static String cloudUrl;
    private static String edgeUrl;

    private static final List<RecordedRequest> hopBackendRequests = new CopyOnWriteArrayList<>();
    private static final List<RecordedRequest> rogueRequests = new CopyOnWriteArrayList<>();

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void startInstances() throws IOException {
        hopBackend = new MockWebServer();
        hopBackend.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                hopBackendRequests.add(request);
                Map<String, Object> parsed = MAPPER.readValue(request.getBody().readUtf8(), Map.class);
                Object headers = parsed.get("headers");
                String body = MAPPER.writeValueAsString(
                        Map.of("receivedHeaders", headers == null ? Map.of() : headers));
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
        hopBackend.start();

        rogue = new MockWebServer();
        rogue.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                rogueRequests.add(request);
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"executionId\":\"rogue-1\",\"status\":\"success\","
                                + "\"output\":\"rogue-ok\",\"error\":null}");
            }
        });
        rogue.start();

        String rogueUrl = baseUrl(rogue);

        cloud = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(instanceArgs("build/test-offload-hopguard-cloud-functions.json"));
        cloudUrl = "http://127.0.0.1:" + port(cloud, "local.server.port");

        edge = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(instanceArgs("build/test-offload-hopguard-edge-functions.json"));
        edgeUrl = "http://127.0.0.1:" + port(edge, "local.server.port");

        // A re-run without a clean build restores FUNCTION from each persisted catalog;
        // drop it so the registration below never collides.
        delete(cloudUrl, FUNCTION);
        delete(edgeUrl, FUNCTION);

        // Cloud: EXTERNAL to hopBackend, but with an eager offload policy toward rogue.
        register(cloudUrl, """
                {"name": "%s", "image": "img", "executionMode": "EXTERNAL",
                 "endpointUrl": "%s", "timeoutMs": 10000,
                 "offload": {"mode": "always", "targetUrl": "%s"}}
                """.formatted(FUNCTION, hopBackend.url("/invoke"), rogueUrl));

        // Edge: eager offload to cloud; never executes locally.
        register(edgeUrl, """
                {"name": "%s", "image": "img", "executionMode": "LOCAL", "timeoutMs": 10000,
                 "offload": {"mode": "always", "targetUrl": "%s"}}
                """.formatted(FUNCTION, cloudUrl));
    }

    private static String[] instanceArgs(String registryPath) {
        return new String[]{
                "--server.port=0",
                "--management.server.port=0",
                "--spring.autoconfigure.exclude="
                        + "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.syncqueue."
                        + "SyncQueueRuntimeConfigAutoConfiguration,"
                        + "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigConfiguration",
                "--sync-queue.enabled=false",
                "--nanofaas.registry.path=" + registryPath
        };
    }

    @AfterAll
    static void stopInstances() throws IOException {
        if (edge != null) {
            edge.close();
        }
        if (cloud != null) {
            cloud.close();
        }
        if (rogue != null) {
            rogue.shutdown();
        }
        if (hopBackend != null) {
            hopBackend.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void offloadedRequestIsNotReOffloadedAndHeadersAreReconstructed() throws Exception {
        int hopBackendBefore = hopBackendRequests.size();
        int rogueBefore = rogueRequests.size();

        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(edgeUrl + "/v1/functions/" + FUNCTION + ":invoke"))
                .header("Content-Type", "application/json")
                .header("x-tenant", "acme")
                .header("X-Trace-Id", "caller-trace")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"ping\"}"))
                .build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
        assertThat(body.get("status")).isEqualTo("success");

        // Re-offload prevention: cloud received the hop marker and executed against its
        // EXTERNAL backend (hopBackend); it did not bounce the request to rogue.
        assertThat(rogueRequests.size())
                .as("the second control plane must not re-offload a request that arrived "
                        + "with the X-NanoFaaS-Offload-Hop marker")
                .isEqualTo(rogueBefore);
        assertThat(hopBackendRequests.size())
                .as("the second control plane should have executed the function against "
                        + "its real EXTERNAL backend")
                .isEqualTo(hopBackendBefore + 1);

        // Tracing preservation across the offload hop: the trace id the local caller set is
        // the one cloud forwarded to the real backend on the third leg.
        RecordedRequest backendRequest = hopBackendRequests.get(hopBackendRequests.size() - 1);
        assertThat(backendRequest.getHeader("X-Trace-Id")).isEqualTo("caller-trace");

        // Reconstruction on the second control plane: the handler (here the hopBackend
        // echo) receives x-tenant because cloud rebuilt InvocationRequest.headers() from
        // the real HTTP headers of the offload hop.
        Object output = body.get("output");
        assertThat(output).as("function output envelope").isInstanceOf(Map.class);
        Map<String, Object> receivedHeaders = (Map<String, Object>) ((Map<String, Object>) output)
                .get("receivedHeaders");
        assertThat(receivedHeaders)
                .as("headers observed by the function on the second control plane")
                .containsEntry("x-tenant", "acme");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutTheHopMarkerTheSameCloudFunctionWouldOffload() throws Exception {
        int rogueBefore = rogueRequests.size();
        int hopBackendBefore = hopBackendRequests.size();

        // Control for the re-offload test: a DIRECT call to cloud's function (no hop marker)
        // must be offloaded to rogue, proving the eager policy is live and that it is the
        // marker — not the function config — that stopped the second hop in
        // offloadedRequestIsNotReOffloadedAndHeadersAreReconstructed.
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(cloudUrl + "/v1/functions/" + FUNCTION + ":invoke"))
                .header("Content-Type", "application/json")
                .header("x-tenant", "acme")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"ping\"}"))
                .build());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(rogueRequests.size())
                .as("a direct call to a cloud function with an eager offload policy must "
                        + "reach the offload target")
                .isEqualTo(rogueBefore + 1);
        assertThat(hopBackendRequests.size()).isEqualTo(hopBackendBefore);
    }

    private static String baseUrl(MockWebServer server) {
        String url = server.url("/").toString();
        return url.substring(0, url.length() - 1);
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
