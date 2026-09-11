package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InvokeHandlerOwnershipTest {

    @Test
    void requestAdmittedAfterStopBeginsGetsTheContractStoppingOutcome() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InvokeHandler handler = new InvokeHandler(
                request -> Map.of("ok", true),
                new CallbackClient(mapper, null),
                new RuntimeMetrics("stopping-admission"),
                mapper,
                "stopping-admission");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/invoke", handler);
        server.start();

        try (HttpClient client = HttpClient.newHttpClient()) {
            handler.shutdownCallbacks();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/invoke"))
                            .header("Content-Type", "application/json")
                            .header("X-Execution-Id", "after-stop")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(
                    mapper.readTree("{\"error\":{\"code\":\"RUNTIME_STOPPING\",\"message\":\"Runtime is stopping\"}}"),
                    mapper.readTree(response.body()));
        } finally {
            server.stop(0);
            handler.shutdownCallbacks();
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void shutdownDrainsAnActiveOwnedCallbackWorkerAndLeavesNoNamedThread() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InvokeHandler handler = new InvokeHandler(
                request -> Map.of("ok", true),
                new CallbackClient(mapper, null),
                new RuntimeMetrics("active-callback"),
                mapper,
                "active-callback");
        ThreadPoolExecutor executor = callbackExecutorOf(handler);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        executor.execute(() -> {
            callbackThread.set(Thread.currentThread());
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(callbackStarted.await(2, TimeUnit.SECONDS));
        FutureTask<Void> stop = new FutureTask<>(() -> {
            handler.shutdownCallbacks();
            return null;
        });
        Thread stopThread = Thread.ofPlatform().start(stop);

        try {
            await().atMost(2, TimeUnit.SECONDS).until(executor::isShutdown);
            assertFalse(stop.isDone(), "shutdown must still own the active callback");
            assertTrue(callbackThread.get().getName().startsWith("nanofaas-lite-callback-"));

            releaseCallback.countDown();

            stop.get(2, TimeUnit.SECONDS);
            await().atMost(2, TimeUnit.SECONDS).until(() -> !callbackThread.get().isAlive());
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
            stopThread.interrupt();
            stopThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void shutdownClosesTheCallbackExecutorCreatedByTheHandler() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InvokeHandler handler = new InvokeHandler(
                request -> Map.of("ok", true),
                new CallbackClient(mapper, null),
                new RuntimeMetrics("owned-callback-executor"),
                mapper,
                "owned-callback-executor");
        ThreadPoolExecutor executor = callbackExecutorOf(handler);

        handler.shutdownCallbacks();

        await().atMost(2, TimeUnit.SECONDS).until(executor::isTerminated);
    }

    @Test
    void shutdownLeavesAnInjectedCallbackExecutorOpen() {
        ThreadPoolExecutor injected = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        ObjectMapper mapper = new ObjectMapper();
        InvokeHandler handler = new InvokeHandler(
                request -> Map.of("ok", true),
                new CallbackClient(mapper, null),
                new RuntimeMetrics("injected-callback-executor"),
                mapper,
                "injected-callback-executor",
                injected,
                1_000);
        try {
            handler.shutdownCallbacks();

            assertFalse(injected.isShutdown(), "the injector retains executor ownership");
        } finally {
            injected.shutdownNow();
        }
    }

    private static ThreadPoolExecutor callbackExecutorOf(InvokeHandler handler) throws Exception {
        Field field = InvokeHandler.class.getDeclaredField("callbackExecutor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(handler);
    }
}
