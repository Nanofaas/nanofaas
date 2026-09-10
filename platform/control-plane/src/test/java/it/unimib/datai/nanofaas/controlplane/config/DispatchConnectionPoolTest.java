package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcher;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DispatchConnectionPoolTest {

    @Test
    void applicationContextOwnsOneAggregatePoolAndExportsItsProvider() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SimpleMeterRegistry.class);
        context.register(HttpClientConfig.class);
        context.refresh();
        DispatchConnectionPool owner = context.getBean(DispatchConnectionPool.class);

        assertThat(context.getBean(ConnectionProvider.class)).isSameAs(owner.provider());

        context.close();
        context.close();
        assertThat(owner.isDisposed()).isTrue();
    }

    @Test
    void oneOwnerAggregatesPoolPopulationConnectionsAndPendingAcquisitions() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HttpClientProperties properties = properties(2, 4, 1_000, 0, 50, 50, 200);
        try (MockWebServer server = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, meters)) {
            server.enqueue(new MockResponse().setBody("{}"));
            server.start();
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());

            assertThat(client.get().uri(server.url("/ok").uri()).retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(3))).isEqualTo("{}");

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(pool.destinationCount()).isOne();
                assertThat(pool.connectionCount()).isOne();
                assertThat(pool.activeConnectionCount()).isZero();
                assertThat(pool.idleConnectionCount()).isOne();
                assertThat(pool.pendingAcquireCount()).isZero();
                assertThat(meters.get("nanofaas_http_pool_destinations").gauge().value()).isEqualTo(1);
                assertThat(meters.get("nanofaas_http_pool_connections").gauge().value()).isEqualTo(1);
                assertThat(meters.get("nanofaas_http_pool_pending_acquisitions").gauge().value()).isZero();
            });
        }
    }

    @Test
    void repeatedCloseIsIdempotentAndDrainsOwnedPools() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HttpClientProperties properties = properties(1, 2, 1_000, 0, 50, 50, 200);
        DispatchConnectionPool pool = new DispatchConnectionPool(properties, meters);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{}"));
            server.start();
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());
            client.get().uri(server.url("/ok").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));

            pool.close();
            pool.close();

            assertThat(pool.isDisposed()).isTrue();
            assertThat(pool.destinationCount()).isZero();
            assertThat(pool.connectionCount()).isZero();
            assertThat(pool.pendingAcquireCount()).isZero();
            assertThatThrownBy(() -> client.get().uri(server.url("/after-close").uri()).retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(3)))
                    .hasStackTraceContaining("dispatch connection pool is closed");
            assertThat(server.getRequestCount()).isOne();
        } finally {
            pool.close();
        }
    }

    @Test
    void idleTimeoutPhysicallyClosesTheConnectionBeforeTheNextRequest() throws Exception {
        HttpClientProperties properties = properties(1, 2, 100, 0, 20, 20, 5_000);
        try (MockWebServer server = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            server.enqueue(new MockResponse().setBody("first"));
            server.enqueue(new MockResponse().setBody("second"));
            server.start();
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());

            client.get().uri(server.url("/first").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber()).isZero();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(pool.connectionCount()).isZero());

            client.get().uri(server.url("/second").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber())
                    .as("idle expiry must force a new physical socket")
                    .isZero();
        }
    }

    @Test
    void configuredMaximumLifetimePhysicallyReplacesTheConnection() throws Exception {
        HttpClientProperties properties = properties(1, 2, 5_000, 150, 20, 20, 5_000);
        try (MockWebServer server = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            server.enqueue(new MockResponse().setBody("first"));
            server.enqueue(new MockResponse().setBody("second"));
            server.start();
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());

            client.get().uri(server.url("/first").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber()).isZero();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(pool.connectionCount()).isZero());

            client.get().uri(server.url("/second").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber())
                    .as("maximum lifetime must force a new physical socket")
                    .isZero();
        }
    }

    @Test
    void zeroMaximumLifetimeLeavesTheConnectionReusable() throws Exception {
        HttpClientProperties properties = properties(1, 2, 5_000, 0, 20, 20, 5_000);
        try (MockWebServer server = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            server.enqueue(new MockResponse().setBody("first"));
            server.enqueue(new MockResponse().setBody("second"));
            server.start();
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());

            client.get().uri(server.url("/first").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber()).isZero();
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(pool.connectionCount()).isOne());

            client.get().uri(server.url("/second").uri()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber())
                    .as("zero disables maximum lifetime, so the physical socket remains reusable")
                    .isEqualTo(1);
        }
    }

    @Test
    void historicalDestinationPoolsDisappearAfterDrainAndTheEvictionWindow() throws Exception {
        HttpClientProperties properties = properties(1, 2, 1_000, 0, 50, 50, 200);
        List<MockWebServer> servers = new ArrayList<>();
        try (DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());
            for (int i = 0; i < 16; i++) {
                MockWebServer server = new MockWebServer();
                servers.add(server);
                server.enqueue(new MockResponse().setBody("{}"));
                server.start();
                client.get().uri(server.url("/churn-" + i).uri()).retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(3));
            }
            assertThat(pool.destinationCount()).isEqualTo(16);
            assertThat(pool.connectionCount()).isEqualTo(16);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(pool.connectionCount())
                        .as("after traffic stops, historical sockets tolerate at most one eviction race")
                        .isLessThanOrEqualTo(1);
                assertThat(pool.destinationCount())
                        .as("after drain plus 2 x 50 ms checks + 200 ms inactivity, tolerate one pool race")
                        .isLessThanOrEqualTo(1);
            });
        } finally {
            for (MockWebServer server : servers) {
                server.close();
            }
        }
    }

    @Test
    void callerCancellationRemovesThePhysicalPendingAcquire() throws Exception {
        BlockingDispatcher backend = new BlockingDispatcher();
        HttpClientProperties properties = properties(1, 4, 5_000, 0, 50, 50, 5_000);
        try (MockWebServer server = server(backend);
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());
            CompletableFuture<String> active = get(client, server, "/active");
            backend.awaitFirstRequest();
            CompletableFuture<String> pending = get(client, server, "/cancelled");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(pool.pendingAcquireCount()).isOne());

            assertThat(pending.cancel(true)).isTrue();
            backend.releaseFirstRequest();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(pool.pendingAcquireCount()).isZero();
                assertThat(pool.activeConnectionCount()).isZero();
                assertThat(pool.connectionCount()).isLessThanOrEqualTo(1);
            });

            assertThat(active.get(3, TimeUnit.SECONDS)).isEqualTo("{}");
            assertThat(backend.requestCount()).isOne();
        }
    }

    @Test
    void acquisitionTimeoutRemovesThePhysicalPendingAcquire() throws Exception {
        BlockingDispatcher backend = new BlockingDispatcher();
        HttpClientProperties properties = new HttpClientProperties(
                1_000, 3_000, 1, 1, 4, 150, 5_000, 0, 50, 50, 5_000);
        try (MockWebServer server = server(backend);
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());
            CompletableFuture<String> active = get(client, server, "/active");
            backend.awaitFirstRequest();

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    client.get().uri(server.url("/timed-out").uri()).retrieve().bodyToMono(String.class)
                            .block(Duration.ofSeconds(3)))
                    .hasStackTraceContaining("Pool#acquire(Duration) has been pending");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(pool.pendingAcquireCount()).isZero());

            backend.releaseFirstRequest();
            assertThat(active.get(3, TimeUnit.SECONDS)).isEqualTo("{}");
            assertThat(backend.requestCount()).isOne();
        }
    }

    @Test
    void functionTimeoutCancelsThePhysicalRequestAndDrainsTheConnection() throws Exception {
        BlockingDispatcher backend = new BlockingDispatcher();
        HttpClientProperties properties = properties(1, 4, 5_000, 0, 50, 50, 5_000);
        try (MockWebServer server = server(backend);
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            ExternalDispatcher dispatcher = new ExternalDispatcher(new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider()));

            CompletableFuture<DispatchResult> request = dispatcher.dispatch(
                    task("function-timeout", server.url("/invoke").toString(), 1_000));
            backend.awaitFirstRequest();

            DispatchResult result = request.get(3, TimeUnit.SECONDS);
            assertThat(result.result().success()).isFalse();
            assertThat(result.result().error().code()).isEqualTo("EXTERNAL_TIMEOUT");
            backend.releaseFirstRequest();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(pool.pendingAcquireCount()).isZero();
                assertThat(pool.activeConnectionCount()).isZero();
                assertThat(pool.connectionCount()).isZero();
            });
            assertThat(backend.requestCount()).isOne();
        }
    }

    @Test
    void responseErrorDrainsItsBodyAndReturnsTheConnectionToThePool() throws Exception {
        HttpClientProperties properties = properties(1, 2, 5_000, 0, 50, 50, 5_000);
        try (MockWebServer server = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("x".repeat(16_384)));
            server.enqueue(new MockResponse().setBody("reused"));
            server.start();
            WebClient client = new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider());

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    client.get().uri(server.url("/error").uri()).retrieve().bodyToMono(String.class)
                            .block(Duration.ofSeconds(3)))
                    .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.class);
            RecordedRequest failed = server.takeRequest(3, TimeUnit.SECONDS);
            assertThat(failed).isNotNull();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(pool.activeConnectionCount()).isZero();
                assertThat(pool.pendingAcquireCount()).isZero();
                assertThat(pool.idleConnectionCount()).isOne();
            });

            assertThat(client.get().uri(server.url("/after-error").uri()).retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(3))).isEqualTo("reused");
            assertThat(server.takeRequest(3, TimeUnit.SECONDS).getSequenceNumber())
                    .as("the fully drained error response leaves the physical socket reusable")
                    .isEqualTo(1);
        }
    }

    @Test
    void endpointReplacementAndRemovalCancelTheOldPendingAcquireWithoutBlockingTheNewHost() throws Exception {
        BlockingDispatcher oldBackend = new BlockingDispatcher();
        HttpClientProperties properties = properties(1, 4, 5_000, 0, 50, 50, 5_000);
        try (MockWebServer oldServer = server(oldBackend);
             MockWebServer replacement = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            replacement.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"replacement\":true}"));
            replacement.start();
            ExternalDispatcher dispatcher = new ExternalDispatcher(new HttpClientConfig().webClient(
                    WebClient.builder(), properties, pool.provider()));

            CompletableFuture<DispatchResult> activeOld = dispatcher.dispatch(
                    task("old-active", oldServer.url("/invoke").toString()));
            oldBackend.awaitFirstRequest();
            CompletableFuture<DispatchResult> removedEndpoint = dispatcher.dispatch(
                    task("old-pending", oldServer.url("/invoke").toString()));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(pool.pendingAcquireCount()).isOne());

            DispatchResult replacementResult = dispatcher.dispatch(
                    task("replacement", replacement.url("/invoke").toString()))
                    .get(3, TimeUnit.SECONDS);
            assertThat(replacementResult.result().success()).isTrue();
            assertThat(removedEndpoint.cancel(true)).isTrue();
            oldBackend.releaseFirstRequest();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(pool.pendingAcquireCount()).isZero());

            assertThat(activeOld.get(3, TimeUnit.SECONDS).result().success()).isTrue();
            assertThat(oldBackend.requestCount()).isOne();
            assertThat(replacement.getRequestCount()).isOne();
            assertThat(pool.destinationCount()).isEqualTo(2);
        }
    }

    private static HttpClientProperties properties(
            int maxConnections,
            int pendingAcquires,
            int maxIdleMs,
            int maxLifeMs,
            int evictionMs,
            int inactiveDisposeMs,
            int poolInactivityMs) {
        return new HttpClientProperties(
                1_000, 10_000, 1, maxConnections, pendingAcquires, 45_000,
                maxIdleMs, maxLifeMs, evictionMs, inactiveDisposeMs, poolInactivityMs);
    }

    private static MockWebServer server(Dispatcher dispatcher) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(dispatcher);
        server.start();
        return server;
    }

    private static CompletableFuture<String> get(WebClient client, MockWebServer server, String path) {
        return client.get().uri(server.url(path).uri()).retrieve().bodyToMono(String.class).toFuture();
    }

    private static InvocationTask task(String executionId, String endpoint) {
        return task(executionId, endpoint, 10_000);
    }

    private static InvocationTask task(String executionId, String endpoint, int timeoutMs) {
        FunctionSpec spec = new FunctionSpec(
                "external", "image", List.of(), Map.of(), null,
                timeoutMs, 1, 10, 0, endpoint, ExecutionMode.EXTERNAL, null, null, null);
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
    }

    private static final class BlockingDispatcher extends Dispatcher {
        private final CountDownLatch firstRequest = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();

        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            if (requests.incrementAndGet() == 1) {
                firstRequest.countDown();
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    return new MockResponse().setResponseCode(504);
                }
            }
            return new MockResponse().setHeader("Content-Type", "application/json").setBody("{}");
        }

        void awaitFirstRequest() throws InterruptedException {
            assertThat(firstRequest.await(3, TimeUnit.SECONDS)).isTrue();
        }

        void releaseFirstRequest() {
            releaseFirst.countDown();
        }

        int requestCount() {
            return requests.get();
        }
    }
}
