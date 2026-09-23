package it.unimib.datai.nanofaas.containerdeployment;



import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RoundRobinFunctionProxyTest {

    private static final Duration REASONABLE_AWAIT = Duration.ofSeconds(5);

    private HttpServer backendA;
    private HttpServer backendB;
    private final List<ExecutorService> executors = new ArrayList<>();
    private RoundRobinFunctionProxy proxy;

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.close();
        }
        stop(backendA);
        stop(backendB);
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void endpointUrl_isStableAndRoundRobinsAcrossBackends() throws Exception {
        backendA = backend("a");
        backendB = backend("b");
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5));
        proxy.updateBackends(List.of(baseUrl(backendA), baseUrl(backendB)));

        HttpClient client = HttpClient.newHttpClient();
        String first = postBody(client, proxy.endpointUrl());
        String second = postBody(client, proxy.endpointUrl());
        String third = postBody(client, proxy.endpointUrl());

        assertThat(proxy.endpointUrl()).startsWith("http://127.0.0.1:");
        assertThat(List.of(first, second, third)).containsExactly("a", "b", "a");
    }

    @Test
    void handleInvoke_emptyBodyUpstream_returnsStatusWithoutChunkedEncoding() throws Exception {
        backendA = backend204();
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(proxy.endpointUrl()))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        // Transfer-Encoding must not be present on a 204 (RFC 7230 §3.3.3)
        assertThat(response.headers().map()).doesNotContainKey("transfer-encoding");
    }

    // --- P1 acceptance: concurrency and admission -------------------------------------------

    @Test
    void latchBlockedBackend_seesAllAdmittedRequestsInFlightBeforeRelease() throws Exception {
        int permits = 4;
        CountDownLatch arrived = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger peak = new AtomicInteger();
        backendA = blockingBackend(arrived, release, peak);
        proxy = new RoundRobinFunctionProxy("127.0.0.1", permits, Duration.ofSeconds(30));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < permits; i++) {
            calls.add(postAsync(client, proxy.endpointUrl()));
        }

        // All four requests must reach the backend while it is still latched: the proxy must
        // forward concurrently (the original defect serialized them, so at most one ever arrived).
        assertThat(arrived.await(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(peak.get()).isEqualTo(permits);

        release.countDown();
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            assertThat(call.get(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void saturatedProxy_rejectsExcessInvocationWith503() throws Exception {
        int permits = 2;
        CountDownLatch arrived = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger peak = new AtomicInteger();
        backendA = blockingBackend(arrived, release, peak);
        proxy = new RoundRobinFunctionProxy("127.0.0.1", permits, Duration.ofSeconds(30));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        List<CompletableFuture<HttpResponse<String>>> admitted = new ArrayList<>();
        for (int i = 0; i < permits; i++) {
            admitted.add(postAsync(client, proxy.endpointUrl()));
        }
        assertThat(arrived.await(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(peak.get()).isEqualTo(permits);

        // The bound is exhausted, so the next invocation must be rejected immediately (defined
        // rejection), never parked waiting for a permit.
        HttpResponse<String> rejected = post(client, proxy.endpointUrl());
        assertThat(rejected.statusCode()).isEqualTo(503);

        release.countDown();
        for (CompletableFuture<HttpResponse<String>> call : admitted) {
            assertThat(call.get(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void updateBackends_switchesTrafficAndNoBackendsYields503() throws Exception {
        backendA = backend("a");
        backendB = backend("b");
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5));
        HttpClient client = HttpClient.newHttpClient();

        proxy.updateBackends(List.of(baseUrl(backendA)));
        assertThat(postBody(client, proxy.endpointUrl())).isEqualTo("a");

        // Backend change: after the switch no request must be routed to the old backend.
        proxy.updateBackends(List.of(baseUrl(backendB)));
        assertThat(postBody(client, proxy.endpointUrl())).isEqualTo("b");
        assertThat(postBody(client, proxy.endpointUrl())).isEqualTo("b");

        // No backends at all is a defined 503, and the proxy stays usable once a backend returns.
        proxy.updateBackends(List.of());
        HttpResponse<String> noBackend = post(client, proxy.endpointUrl());
        assertThat(noBackend.statusCode()).isEqualTo(503);

        proxy.updateBackends(List.of(baseUrl(backendA)));
        assertThat(postBody(client, proxy.endpointUrl())).isEqualTo("a");
    }

    @Test
    void health_doesNotConsumeInvocationPermits_andStaysUpDuringBlockedInvocations() throws Exception {
        int permits = 2;
        CountDownLatch arrived = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        backendA = blockingBackend(arrived, release, new AtomicInteger());
        proxy = new RoundRobinFunctionProxy("127.0.0.1", permits, Duration.ofSeconds(30));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        List<CompletableFuture<HttpResponse<String>>> invocations = new ArrayList<>();
        for (int i = 0; i < permits; i++) {
            invocations.add(postAsync(client, proxy.endpointUrl()));
        }
        assertThat(arrived.await(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // Every invocation permit is held by a slow request; health must still answer and must
        // not be throttled by the invocation admission bound.
        HttpResponse<String> health = get(client, healthUrl(proxy));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("UP");

        release.countDown();
        for (CompletableFuture<HttpResponse<String>> call : invocations) {
            assertThat(call.get(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
        }
    }

    // --- P1 acceptance: timeout -------------------------------------------------------------

    @Test
    void outboundRequest_carriesFunctionTimeoutGreaterThan30Seconds() throws Exception {
        HttpClient backendClient = mock(HttpClient.class);
        List<Optional<Duration>> observedTimeouts = new ArrayList<>();
        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            observedTimeouts.add(request.timeout());
            throw new IOException("abort-after-capture");
        }).when(backendClient).send(any(), any());

        backendA = backend("a"); // any live backend: the outbound send is aborted by the mock
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5), backendClient);
        proxy.updateBackends(List.of(baseUrl(backendA)));
        // A function timeout greater than the removed hard-coded 30 s cap.
        proxy.updateLimits(4, Duration.ofMillis(45_000));

        HttpResponse<String> response = post(HttpClient.newHttpClient(), proxy.endpointUrl());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(observedTimeouts).singleElement().isEqualTo(Optional.of(Duration.ofMillis(45_000)));
    }

    @Test
    void singleHopTimeout_returns504_whenBackendExceedsIt() throws Exception {
        backendA = delayedBackend(Duration.ofSeconds(2));
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofMillis(300));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> timedOut = post(client, proxy.endpointUrl());

        assertThat(timedOut.statusCode()).isEqualTo(504);
        assertThat(timedOut.body()).contains("timed out");
    }

    // --- P1 acceptance: disconnection and shutdown ------------------------------------------

    @Test
    void backendDisconnectMidRequest_returns502_andProxyRecoversOnNewBackend() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        backendA = blockingBackend(entered, neverReleased, new AtomicInteger());
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(30));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> inFlight = postAsync(client, proxy.endpointUrl());
        assertThat(entered.await(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // Abruptly take the backend down while the request is in flight.
        stop(backendA);
        backendA = null;

        HttpResponse<String> afterDisconnect = inFlight.get(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(afterDisconnect.statusCode()).isEqualTo(502);

        // The proxy survives the error and serves a replacement backend.
        backendB = backend("recovered");
        proxy.updateBackends(List.of(baseUrl(backendB)));
        HttpResponse<String> recovered = post(client, proxy.endpointUrl());
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(recovered.body()).isEqualTo("recovered");
    }

    @Test
    void close_shutsDownServerAndHttpClient_andIsIdempotent() throws Exception {
        HttpClient injectedClient = HttpClient.newHttpClient();
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5), injectedClient);
        int port = portOf(proxy);

        proxy.close();
        proxy.close(); // idempotent

        assertThatThrownBy(() -> new Socket("127.0.0.1", port)).isInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> injectedClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/")).GET().build(),
                HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
    }

    @Test
    void close_interruptsInFlightRequest_andReturnsPromptlyInsteadOfWaitingOutTheTimeout()
            throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        backendA = blockingBackend(arrived, neverReleased, new AtomicInteger());
        // A long hop timeout: if close() merely shut the executor down and waited for the handler,
        // it would block until this timeout expired.
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(60));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> inFlight = postAsync(client, proxy.endpointUrl());
        assertThat(arrived.await(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        long start = System.nanoTime();
        proxy.close();
        long closeMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(closeMs).isLessThan(3_000);

        // The client is unblocked promptly (connection terminated), it does not hang.
        CompletableFuture<HttpResponse<String>> finished = inFlight
                .orTimeout(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null);
        assertThat(finished.get()).isNull();
    }

    @Test
    void close_stageFailure_stillReleasesTheOtherStages_andStaysRetryable() throws Exception {
        HttpClient failingClient = mock(HttpClient.class);
        doThrow(new IllegalStateException("client close failed")).doNothing().when(failingClient).close();
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5), failingClient);
        int port = portOf(proxy);

        assertThatThrownBy(proxy::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("client close failed");

        // The stages before the failing one did run: the listening socket is gone even though the
        // close as a whole did not finish. A skipped stage would have no other owner left to run it.
        assertThatThrownBy(() -> new Socket("127.0.0.1", port)).isInstanceOf(ConnectException.class);

        // The failed close left the proxy unreleased, so the owner's retry re-runs every stage.
        proxy.close();
        verify(failingClient, times(2)).close();
    }

    @Test
    void close_afterAnErrorPath_stillShutsDownCleanly() throws Exception {
        backendA = backend("a");
        proxy = new RoundRobinFunctionProxy("127.0.0.1", 4, Duration.ofSeconds(5));
        proxy.updateBackends(List.of(baseUrl(backendA)));

        // Exercise a healthy round trip, then an error path (backend removed), then close.
        assertThat(post(HttpClient.newHttpClient(), proxy.endpointUrl()).body()).isEqualTo("a");
        proxy.updateBackends(List.of());
        HttpResponse<String> noBackend = post(HttpClient.newHttpClient(), proxy.endpointUrl());
        assertThat(noBackend.statusCode()).isEqualTo(503);

        int port = portOf(proxy);
        proxy.close();
        assertThatThrownBy(() -> new Socket("127.0.0.1", port)).isInstanceOf(ConnectException.class);
    }

    // --- helpers ----------------------------------------------------------------------------

    private static HttpServer backend(String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoke", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        return server;
    }

    private static HttpServer backend204() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoke", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
        });
        server.start();
        return server;
    }

    private HttpServer delayedBackend(Duration delay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executors.add(executor);
        server.setExecutor(executor);
        server.createContext("/invoke", exchange -> {
            try {
                Thread.sleep(delay.toMillis()); // NOSONAR (java:S2925): simulates a slow backend
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = "slow".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        return server;
    }

    /**
     * A backend whose {@code /invoke} handler blocks on {@code release} once {@code arrived} has
     * been counted down, and reports the peak number of handlers running concurrently.
     */
    private HttpServer blockingBackend(CountDownLatch arrived,
                                       CountDownLatch release,
                                       AtomicInteger peak) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executors.add(executor);
        server.setExecutor(executor);
        AtomicInteger active = new AtomicInteger();
        server.createContext("/invoke", exchange -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                arrived.countDown();
                if (!release.await(REASONABLE_AWAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.getResponseBody().close();
                    return;
                }
                byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(response);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        });
        server.start();
        return server;
    }

    private static void stop(HttpServer server) {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static int portOf(RoundRobinFunctionProxy proxy) {
        return Integer.parseInt(proxy.endpointUrl().substring(
                proxy.endpointUrl().lastIndexOf(':') + 1, proxy.endpointUrl().lastIndexOf('/')));
    }

    private static String healthUrl(RoundRobinFunctionProxy proxy) {
        String endpoint = proxy.endpointUrl();
        return endpoint.substring(0, endpoint.lastIndexOf('/')) + "/health";
    }

    private static HttpResponse<String> post(HttpClient client, String url) throws Exception {
        return client.send(postRequest(url), HttpResponse.BodyHandlers.ofString());
    }

    private static String postBody(HttpClient client, String url) throws Exception {
        return post(client, url).body();
    }

    private static CompletableFuture<HttpResponse<String>> postAsync(HttpClient client, String url) {
        return client.sendAsync(postRequest(url), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest postRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"ok\"}"))
                .header("Content-Type", "application/json")
                .build();
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
