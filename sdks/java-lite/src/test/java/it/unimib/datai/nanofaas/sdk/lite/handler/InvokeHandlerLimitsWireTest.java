package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class InvokeHandlerLimitsWireTest {
    @Test
    void inputAndOutputCapsUseCanonicalWireOutcomes() throws Exception {
        try (Harness input = new Harness(_ -> Map.of("ok", true),
                new RuntimeLimits(1, 1, 128, 16, 64, 128))) {
            var response = input.invoke("{\"input\":\"01234567890123456789\"}");
            assertEquals(413, response.statusCode());
            assertEquals("RUNTIME_INPUT_TOO_LARGE", input.code(response));
        }
        try (Harness output = new Harness(_ -> Map.of("value", "01234567890123456789"),
                new RuntimeLimits(1, 1, 128, 64, 16, 128))) {
            var response = output.invoke("{\"input\":{}}");
            assertEquals(500, response.statusCode());
            assertEquals("RUNTIME_OUTPUT_TOO_LARGE", output.code(response));
        }
    }

    @Test
    void callbackAndHandlerSaturationAreDistinctRetryableOutcomes() throws Exception {
        assertSaturation(new RuntimeLimits(2, 1, 128, 64, 64, 128), "RUNTIME_CALLBACK_SATURATED");
        assertSaturation(new RuntimeLimits(1, 2, 256, 64, 64, 128), "RUNTIME_HANDLER_SATURATED");
    }

    @Test
    void boundedStopReleasesAFullOwnedCallbackQueueAndTheActiveCallbackOnExit() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RuntimeLimits limits = new RuntimeLimits(2, 2, 256, 64, 64, 128);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ExecutorService callbackServerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.setExecutor(callbackServerExecutor);
        callbackServer.createContext("/", exchange -> {
            callbackEntered.countDown();
            try {
                releaseCallback.await();
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        callbackServer.start();

        ThreadPoolExecutor callbackExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CallbackClient callbackClient = new CallbackClient(mapper,
                "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/v1/executions");
        InvokeHandler handler = new InvokeHandler(_ -> Map.of("ok", true), callbackClient,
                new RuntimeMetrics("callback-stop"), mapper, "callback-stop",
                callbackExecutor, 1_000, limits, true);
        ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer invokeServer = HttpServer.create(new InetSocketAddress(0), 0);
        invokeServer.setExecutor(requestExecutor);
        invokeServer.createContext("/invoke", handler);
        invokeServer.start();
        HttpClient client = HttpClient.newHttpClient();

        try {
            assertEquals(200, invoke(client, invokeServer, "first").statusCode());
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertEquals(200, invoke(client, invokeServer, "second").statusCode());
            assertEquals(2, limits.pendingCallbacks());

            assertTrue(handler.shutdown(java.time.Duration.ofMillis(20)));
            assertEquals(0, limits.pendingCallbacks(),
                    "shutdown must coordinate the interrupted active callback and release the queued callback");
            assertEquals(0, limits.pendingCallbackBytes());
        } finally {
            releaseCallback.countDown();
            handler.shutdown(java.time.Duration.ofSeconds(1));
            callbackClient.close(java.time.Duration.ofSeconds(1));
            invokeServer.stop(0);
            callbackServer.stop(0);
            callbackExecutor.shutdownNow();
            requestExecutor.shutdownNow();
            callbackServerExecutor.shutdownNow();
        }
    }

    @Test
    void stopBetweenHandlerReservationAndRegistrationReleasesThePermit() throws Exception {
        RuntimeLimits limits = new RuntimeLimits(1, 1, 128, 64, 64, 128);
        try (Harness harness = new Harness(_ -> Map.of("ok", true), limits)) {
            Object lifecycle = field(harness.handler, "handlerLifecycle");
            GatedBodyPublisher body = new GatedBodyPublisher("{\"input\":{}}");
            Future<HttpResponse<String>> response = harness.requests.submit(() -> harness.invoke(body));
            await(() -> limits.pendingCallbacks() == 1);
            synchronized (lifecycle) {
                body.release();
                await(() -> limits.activeHandlers() == 1);
                harness.handler.beginStop();
            }

            HttpResponse<String> stopped = response.get(1, TimeUnit.SECONDS);
            assertEquals(503, stopped.statusCode());
            assertEquals("RUNTIME_STOPPING", harness.code(stopped));
            assertEquals(0, limits.activeHandlers());
        }
    }

    private static HttpResponse<String> invoke(HttpClient client, HttpServer server, String executionId)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/invoke"))
                .header("Content-Type", "application/json").header("X-Execution-Id", executionId)
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition) {
        org.awaitility.Awaitility.await().pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(10))
                .atMost(Duration.ofSeconds(2)).until(condition::getAsBoolean);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class GatedBodyPublisher implements HttpRequest.BodyPublisher {
        private final byte[] bytes;
        private final CountDownLatch released = new CountDownLatch(1);

        private GatedBodyPublisher(String body) {
            bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public long contentLength() { return bytes.length; }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean started = new AtomicBoolean();
                @Override public void request(long count) {
                    if (count <= 0 || !started.compareAndSet(false, true)) return;
                    Thread.startVirtualThread(() -> {
                        try {
                            released.await();
                            subscriber.onNext(ByteBuffer.wrap(bytes));
                            subscriber.onComplete();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            subscriber.onError(ex);
                        }
                    });
                }
                @Override public void cancel() { released.countDown(); }
            });
        }

        private void release() { released.countDown(); }
    }

    private static void assertSaturation(RuntimeLimits limits, String expected) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Harness harness = new Harness(_ -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            return Map.of("ok", true);
        }, limits)) {
            Future<HttpResponse<String>> first = harness.requests.submit(() -> harness.invoke("{\"input\":{}}"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            HttpResponse<String> rejected = harness.invoke("{\"input\":{}}");
            assertEquals(429, rejected.statusCode());
            assertEquals("1", rejected.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(expected, harness.code(rejected));
            release.countDown();
            assertEquals(200, first.get(1, TimeUnit.SECONDS).statusCode());
        } finally {
            release.countDown();
        }
    }

    private static final class Harness implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2));
        final InvokeHandler handler;
        final HttpServer server;
        final HttpClient client = HttpClient.newHttpClient();

        Harness(FunctionHandler function, RuntimeLimits limits) throws Exception {
            handler = new InvokeHandler(function, new CallbackClient(mapper, null),
                    new RuntimeMetrics("limits-wire"), mapper, "limits-wire", callbacks, 1_000, limits);
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(requests);
            server.createContext("/invoke", handler);
            server.start();
        }

        HttpResponse<String> invoke(String body) throws Exception {
            return invoke(HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> invoke(HttpRequest.BodyPublisher body) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/invoke"))
                    .header("Content-Type", "application/json").header("X-Execution-Id", "exec")
                    .POST(body).build(), HttpResponse.BodyHandlers.ofString());
        }
        String code(HttpResponse<String> response) throws Exception {
            return mapper.readTree(response.body()).path("error").path("code").asText();
        }
        @Override public void close() {
            handler.beginStop();
            server.stop(0);
            handler.shutdown(java.time.Duration.ofSeconds(1));
            callbacks.shutdownNow();
            requests.shutdownNow();
        }
    }
}
