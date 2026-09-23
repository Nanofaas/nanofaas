package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvokeHandlerInterruptedCallbackShutdownTest {
    @Test
    void boundedShutdownWaitsForInterruptedCallbackToReleaseReservation() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        callbackServer.createContext("/", exchange -> {
            callbackEntered.countDown();
            try {
                Thread.sleep(10_000); // NOSONAR (java:S2925): simulates a slow backend
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        callbackServer.start();

        RuntimeLimits limits = new RuntimeLimits(1, 1, 1_024, 1_024, 1_024, 1_024,
                1_000, 1_000, 3, 60);
        ThreadPoolExecutor callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        ObjectMapper mapper = new ObjectMapper();
        InvokeHandler handler = new InvokeHandler(_ -> Map.of("result", "ok"),
                new CallbackClient(mapper, "http://127.0.0.1:" + callbackServer.getAddress().getPort()),
                new RuntimeMetrics("interrupt-stop"), mapper, "interrupt-stop", callbacks, 1_000,
                limits, true);
        HttpServer runtime = HttpServer.create(new InetSocketAddress(0), 0);
        runtime.createContext("/invoke", handler);
        runtime.start();
        try { // NOSONAR (java:S2093): HttpServer is not AutoCloseable; teardown order matters
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + runtime.getAddress().getPort() + "/invoke"))
                    .header("Content-Type", "application/json")
                    .header("X-Execution-Id", "execution")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, limits.pendingCallbacks());

            long started = System.nanoTime();
            assertTrue(handler.shutdown(Duration.ofMillis(60)));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 500, "stop must remain bounded while coordinating interruption");
            assertEquals(0, limits.pendingCallbacks());
            assertEquals(0, limits.pendingCallbackBytes());
            assertTrue(callbacks.isTerminated());
        } finally {
            runtime.stop(0);
            handler.shutdown(Duration.ofMillis(60));
            callbackServer.stop(0);
        }
    }

    @Test
    void stopPreservesCallerInterruptWhileStillDrainingCallbackOwnership() throws Exception {
        RuntimeLimits limits = new RuntimeLimits(1, 1, 1_024, 1_024, 1_024, 1_024);
        ThreadPoolExecutor callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        ObjectMapper mapper = new ObjectMapper();
        InvokeHandler handler = new InvokeHandler(_ -> Map.of("result", "ok"),
                new CallbackClient(mapper, null), new RuntimeMetrics("interrupt-entry"), mapper,
                "interrupt-entry", callbacks, 1_000, limits, true);
        Thread.currentThread().interrupt();
        try {
            assertTrue(handler.shutdown(Duration.ofMillis(60)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, limits.pendingCallbacks());
            assertEquals(0, limits.pendingCallbackBytes());
        } finally {
            Thread.interrupted();
            callbacks.shutdownNow();
        }
    }
}
