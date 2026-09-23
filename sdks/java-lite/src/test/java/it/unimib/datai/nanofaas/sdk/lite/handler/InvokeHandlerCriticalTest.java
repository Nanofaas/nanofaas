package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvokeHandlerCriticalTest {
    @Test
    void malformedJsonTransfersReservationUntilPhysicalCallbackExit() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        try (Harness harness = Harness.withCallback(_ -> Map.of("unused", true), new ObjectMapper(),
                callbackEntered, releaseCallback)) {
            HttpResponse<String> response = harness.invoke("{");

            assertEquals(400, response.statusCode());
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, harness.limits.pendingCallbacks(),
                    "the callback owns the reservation after malformed JSON handoff");

            releaseCallback.countDown();
            await(() -> harness.limits.pendingCallbacks() == 0);
        } finally {
            releaseCallback.countDown();
        }
    }

    @Test
    void callbackQueueRejectionAfterHandlerNeverReturnsSuccess() throws Exception {
        try (Harness harness = Harness.withoutCallback(_ -> Map.of("result", "ok"), new ObjectMapper())) {
            harness.callbacks.shutdown();

            HttpResponse<String> response = harness.invoke("{\"input\":{}}");

            assertEquals(503, response.statusCode());
            assertEquals("RUNTIME_STOPPING", harness.code(response));
            assertEquals(0, harness.limits.pendingCallbacks());
        }
    }

    @Test
    void customOutputIsRejectedBeforeUnlimitedTraversalOrTreeMaterialization() throws Exception {
        AtomicInteger emitted = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(StreamedValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(StreamedValue value, JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                generator.writeStartArray();
                for (int index = 0; index < 10_000; index++) {
                    generator.writeString("0123456789");
                    emitted.incrementAndGet();
                }
                generator.writeEndArray();
            }
        });
        ObjectMapper mapper = new ObjectMapper().registerModule(module);
        try (Harness harness = Harness.withoutCallback(_ -> new StreamedValue(), mapper, 64)) {
            HttpResponse<String> response = harness.invoke("{\"input\":{}}");

            assertEquals(500, response.statusCode());
            assertEquals("RUNTIME_OUTPUT_TOO_LARGE", harness.code(response));
            assertTrue(emitted.get() < 2_048);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        org.awaitility.Awaitility.await().pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(5))
                .atMost(Duration.ofSeconds(2)).until(condition::getAsBoolean);
    }

    private static final class StreamedValue { }

    private static final class Harness implements AutoCloseable {
        private final ObjectMapper mapper;
        private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        private final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        private final RuntimeLimits limits;
        private final InvokeHandler handler;
        private final HttpServer invokeServer;
        private final HttpServer callbackServer;
        private final ExecutorService callbackServerExecutor;
        private final CallbackClient callbackClient;

        private Harness(FunctionHandler function, ObjectMapper mapper, int outputLimit,
                        CountDownLatch callbackEntered, CountDownLatch releaseCallback) throws Exception {
            this.mapper = mapper;
            this.limits = new RuntimeLimits(1, 2, 2_048, 128, outputLimit, 1_024);
            if (callbackEntered == null) {
                callbackServer = null;
                callbackServerExecutor = null;
                callbackClient = new CallbackClient(mapper, null);
            } else {
                callbackServerExecutor = Executors.newVirtualThreadPerTaskExecutor();
                callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
                callbackServer.setExecutor(callbackServerExecutor);
                callbackServer.createContext("/", exchange -> {
                    callbackEntered.countDown();
                    try {
                        releaseCallback.await();
                        exchange.sendResponseHeaders(204, -1);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                callbackServer.start();
                callbackClient = new CallbackClient(mapper,
                        "http://127.0.0.1:" + callbackServer.getAddress().getPort());
            }
            handler = new InvokeHandler(function, callbackClient, new RuntimeMetrics("critical"), mapper,
                    "critical", callbacks, 1_000, limits);
            invokeServer = HttpServer.create(new InetSocketAddress(0), 0);
            invokeServer.setExecutor(requests);
            invokeServer.createContext("/invoke", handler);
            invokeServer.start();
        }

        static Harness withoutCallback(FunctionHandler function, ObjectMapper mapper) throws Exception {
            return withoutCallback(function, mapper, 128);
        }

        static Harness withoutCallback(FunctionHandler function, ObjectMapper mapper, int outputLimit)
                throws Exception {
            return new Harness(function, mapper, outputLimit, null, null);
        }

        static Harness withCallback(FunctionHandler function, ObjectMapper mapper,
                                    CountDownLatch entered, CountDownLatch release) throws Exception {
            return new Harness(function, mapper, 128, entered, release);
        }

        HttpResponse<String> invoke(String body) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + invokeServer.getAddress().getPort() + "/invoke"))
                    .header("Content-Type", "application/json")
                    .header("X-Execution-Id", "exec")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        String code(HttpResponse<String> response) throws Exception {
            return mapper.readTree(response.body()).path("error").path("code").asText();
        }

        @Override
        public void close() {
            invokeServer.stop(0);
            handler.shutdown(java.time.Duration.ofSeconds(1));
            callbackClient.close(java.time.Duration.ofSeconds(1));
            callbacks.shutdownNow();
            requests.shutdownNow();
            if (callbackServer != null) callbackServer.stop(0);
            if (callbackServerExecutor != null) callbackServerExecutor.shutdownNow();
        }
    }
}
