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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end validation of the pressure offload triggers: an "edge" control
 * plane saturated through its sync queue (DEPTH rejection) transparently
 * proxies the overflow to a "cloud" control plane instead of answering 429.
 * The eager trigger and failure semantics are covered by unit tests and the
 * two-instance smoke recorded in the design spec; this test exercises the
 * strategy that cannot be validated without real queue pressure.
 */
class OffloadPressureE2eTest {

    private static final String FUNCTION = "pressure-echo";

    private static MockWebServer slowPod;
    private static ConfigurableApplicationContext cloud;
    private static ConfigurableApplicationContext edge;
    private static String cloudUrl;
    private static String edgeUrl;
    private static String edgeManagementUrl;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeAll
    static void startInstances() throws IOException {
        slowPod = new MockWebServer();
        slowPod.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setHeader("Content-Type", "text/plain")
                        .setBody("slow-local-ok")
                        .setBodyDelay(2, TimeUnit.SECONDS);
            }
        });
        slowPod.start();

        // command-line args: builder .properties() would lose to application.yml
        cloud = new SpringApplicationBuilder(ControlPlaneApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                // no admission throughput history exists in a fresh instance:
                // est-wait would falsely 429 the offloads
                "--sync-queue.enabled=false");
        cloudUrl = "http://127.0.0.1:" + port(cloud, "local.server.port");

        edge = new SpringApplicationBuilder(ControlPlaneApplication.class).run(
                "--server.port=0",
                "--management.server.port=0",
                "--nanofaas.offload.target-url=" + cloudUrl,
                "--sync-queue.enabled=true",
                // deterministic DEPTH trigger: tiny queue, no est-wait
                "--sync-queue.max-depth=1",
                "--sync-queue.admission-enabled=false",
                // queued work legitimately waits behind the 2s slow pod
                "--sync-queue.max-queue-wait=30s");
        edgeUrl = "http://127.0.0.1:" + port(edge, "local.server.port");
        edgeManagementUrl = "http://127.0.0.1:" + port(edge, "local.management.port");

        register(cloudUrl, """
                {"name": "%s", "image": "img", "executionMode": "LOCAL", "timeoutMs": 10000}
                """.formatted(FUNCTION));
        register(edgeUrl, """
                {"name": "%s", "image": "img", "executionMode": "EXTERNAL",
                 "endpointUrl": "%s", "timeoutMs": 10000, "concurrency": 1, "queueSize": 10}
                """.formatted(FUNCTION, slowPod.url("/invoke")));
    }

    @AfterAll
    static void stopInstances() throws IOException {
        if (edge != null) {
            edge.close();
        }
        if (cloud != null) {
            cloud.close();
        }
        if (slowPod != null) {
            slowPod.shutdown();
        }
    }

    @Test
    void saturatedSyncQueueOffloadsOverflowToTheCloudInstance() throws Exception {
        // occupy the single concurrency slot and the single queue position
        CompletableFuture<HttpResponse<String>> first = invokeAsync("occupies-slot");
        awaitLocalDispatchStarted();
        CompletableFuture<HttpResponse<String>> second = invokeAsync("fills-queue");

        // keep invoking until the queue is provably full and DEPTH kicks in:
        // non-offloaded attempts simply queue up and complete locally
        HttpResponse<String> offloaded = await().atMost(Duration.ofSeconds(30)).until(
                () -> invoke("overflow"),
                response -> response.headers().firstValue("X-NanoFaaS-Offloaded").isPresent());

        assertThat(offloaded.statusCode()).isEqualTo(200);
        assertThat(offloaded.body()).contains("\"status\":\"success\"");
        assertThat(offloaded.body()).contains("overflow");
        assertThat(offloaded.headers().firstValue("X-NanoFaaS-Offloaded")).contains(cloudUrl);

        // the queued local work still completes locally against the slow pod
        assertThat(first.get(15, TimeUnit.SECONDS).body()).contains("slow-local-ok");
        assertThat(second.get(15, TimeUnit.SECONDS).body()).contains("slow-local-ok");

        String metrics = get(edgeManagementUrl + "/actuator/prometheus");
        assertThat(metrics)
                .contains(
                        "nanofaas_offload_total{function=\"" + FUNCTION + "\",trigger=\"depth\"}")
                .doesNotContain("nanofaas_offload_failure_total");
        assertThat(metric(metrics, "function_retry_total{function=\"" + FUNCTION + "\"}"))
                .isEqualTo(0.0);
    }

    private static void awaitLocalDispatchStarted() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(slowPod.getRequestCount()).isGreaterThanOrEqualTo(1));
    }

    private static double metric(String metrics, String prefix) {
        return metrics.lines()
                .filter(line -> line.startsWith(prefix))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElse(0.0);
    }

    private static int port(ConfigurableApplicationContext context, String property) {
        String value = context.getEnvironment().getProperty(property);
        assertThat(value).as(property).isNotNull();
        return Integer.parseInt(value);
    }

    private static void register(String baseUrl, String spec) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/functions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(spec))
                .build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private static HttpResponse<String> invoke(String input) throws IOException {
        return send(invocationRequest(input));
    }

    private static CompletableFuture<HttpResponse<String>> invokeAsync(String input) {
        return http.sendAsync(invocationRequest(input), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest invocationRequest(String input) {
        return HttpRequest.newBuilder(
                        URI.create(edgeUrl + "/v1/functions/" + FUNCTION + ":invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"" + input + "\"}"))
                .build();
    }

    private static String get(String url) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET().build()).body();
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
