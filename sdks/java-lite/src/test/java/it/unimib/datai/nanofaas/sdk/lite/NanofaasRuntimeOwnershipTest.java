package it.unimib.datai.nanofaas.sdk.lite;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NanofaasRuntimeOwnershipTest {

    @Test
    void concurrentStopCannotPassAnUnpublishedShutdownHook() throws Exception {
        CountDownLatch addEntered = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        NanofaasRuntime.ShutdownHooks hooks = new NanofaasRuntime.ShutdownHooks() {
            @Override
            public void add(Thread hook) {
                addEntered.countDown();
                try {
                    releaseAdd.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                added.incrementAndGet();
            }

            @Override
            public void remove(Thread hook) {
                removed.incrementAndGet();
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NanofaasRuntime runtime = baseBuilder(availablePort(), "concurrent-hook-stop")
                .serverExecutorFactory(() -> executor)
                .shutdownHooks(hooks)
                .build();
        Thread startThread = Thread.ofPlatform().start(runtime::start);
        assertTrue(addEntered.await(2, TimeUnit.SECONDS));
        CountDownLatch stopEntered = new CountDownLatch(1);
        FutureTask<Void> stop = new FutureTask<>(() -> {
            stopEntered.countDown();
            runtime.stop();
            return null;
        });
        Thread stopThread = Thread.ofPlatform().start(stop);

        try {
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            await().atMost(2, TimeUnit.SECONDS).until(() ->
                    stop.isDone() || stopThread.getState() == Thread.State.BLOCKED);
            assertFalse(stop.isDone(), "stop must not pass hook publication");
            assertEquals(Thread.State.BLOCKED, stopThread.getState());
            releaseAdd.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertTrue(startThread.join(Duration.ofSeconds(2)));

            assertEquals(1, added.get());
            assertEquals(1, removed.get(), "every published hook must be removed by completed stop");
            await().atMost(2, TimeUnit.SECONDS).until(executor::isTerminated);
        } finally {
            releaseAdd.countDown();
            runtime.stop();
            executor.shutdownNow();
            startThread.interrupt();
            stopThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
            stopThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void shutdownHookRegistrationFailureCleansAlreadyOwnedResources() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CallbackClient callbackClient = new CallbackClient(new ObjectMapper(), "http://127.0.0.1:1");
        HttpClient ownedHttpClient = httpClientOf(callbackClient);
        int port = availablePort();
        NanofaasRuntime.ShutdownHooks rejectingHooks = new NanofaasRuntime.ShutdownHooks() {
            @Override
            public void add(Thread hook) {
                throw new IllegalStateException("hook registration rejected");
            }

            @Override
            public void remove(Thread hook) {
                throw new AssertionError("an unpublished hook must not be removed");
            }
        };
        NanofaasRuntime runtime = baseBuilder(port, "hook-registration-failure")
                .callbackClientFactory(() -> callbackClient)
                .serverExecutorFactory(() -> executor)
                .shutdownHooks(rejectingHooks)
                .build();

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::start);

        assertEquals("hook registration rejected", failure.getMessage());
        await().atMost(2, TimeUnit.SECONDS).until(executor::isTerminated);
        await().atMost(2, TimeUnit.SECONDS).until(ownedHttpClient::isTerminated);
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .build()) {
            assertThrows(IOException.class, () -> client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofSeconds(1))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding()));
        } finally {
            runtime.stop();
            executor.shutdownNow();
            callbackClient.close();
        }
    }

    @Test
    void stopClosesTheRuntimeOwnedServerExecutorAndItsControlledThread() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "p18-owned-http-worker");
            worker.set(thread);
            return thread;
        });
        int port = availablePort();
        NanofaasRuntime.Builder builder = baseBuilder(port, "owned-server-executor");
        configureBuilder(builder, "serverExecutorFactory", Supplier.class,
                (Supplier<ExecutorService>) () -> executor);
        NanofaasRuntime runtime = builder.build();
        Thread startThread = Thread.ofPlatform().start(runtime::start);

        try (HttpClient client = HttpClient.newHttpClient()) {
            awaitHealth(client, port);
            assertTrue(worker.get().isAlive());

            runtime.stop();

            await().atMost(2, TimeUnit.SECONDS).until(executor::isTerminated);
            await().atMost(2, TimeUnit.SECONDS).until(() -> !worker.get().isAlive());
        } finally {
            runtime.stop();
            executor.shutdownNow();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void repeatedStartStopAndDoubleStopReleaseOnlyOwnedThreadsAndSockets() throws Exception {
        for (int cycle = 0; cycle < 3; cycle++) {
            int port = availablePort();
            AtomicReference<Thread> worker = new AtomicReference<>();
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "p18-cycle-http-" + port);
                worker.set(thread);
                return thread;
            });
            NanofaasRuntime runtime = baseBuilder(port, "cycle-" + cycle)
                    .serverExecutorFactory(() -> executor)
                    .build();
            Thread startThread = Thread.ofPlatform().start(runtime::start);

            try (HttpClient client = HttpClient.newHttpClient()) {
                awaitHealth(client, port);

                runtime.stop();
                runtime.stop();

                assertTrue(startThread.join(Duration.ofSeconds(2)));
                await().atMost(2, TimeUnit.SECONDS).until(executor::isTerminated);
                await().atMost(2, TimeUnit.SECONDS).until(() -> !worker.get().isAlive());
                await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThrows(IOException.class, () -> client.send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/health"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())));
            } finally {
                runtime.stop();
                executor.shutdownNow();
                startThread.interrupt();
                startThread.join(TimeUnit.SECONDS.toMillis(2));
            }
        }
    }

    @Test
    void stopLeavesAnInjectedServerExecutorOpen() throws Exception {
        ExecutorService injected = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "p18-injected-http-worker"));
        int port = availablePort();
        NanofaasRuntime.Builder builder = baseBuilder(port, "injected-server-executor");
        configureBuilder(builder, "serverExecutor", ExecutorService.class, injected);
        NanofaasRuntime runtime = builder.build();
        Thread startThread = Thread.ofPlatform().start(runtime::start);

        try (HttpClient client = HttpClient.newHttpClient()) {
            awaitHealth(client, port);

            runtime.stop();

            assertFalse(injected.isShutdown(), "the injector retains executor ownership");
        } finally {
            runtime.stop();
            injected.shutdownNow();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void bindFailureClosesResourcesCreatedBeforeTheServer() throws Exception {
        CallbackClient callbackClient = new CallbackClient(
                new ObjectMapper(), "http://127.0.0.1:1");
        HttpClient ownedHttpClient = httpClientOf(callbackClient);
        try (ServerSocket occupied = new ServerSocket(0)) {
            NanofaasRuntime.Builder builder = NanofaasRuntime.builder()
                    .handler(request -> Map.of("ok", true))
                    .functionName("bind-failure-test")
                    .port(occupied.getLocalPort());
            Method factoryMethod = builder.getClass()
                    .getDeclaredMethod("callbackClientFactory", Supplier.class);
            factoryMethod.setAccessible(true);
            factoryMethod.invoke(builder, (Supplier<CallbackClient>) () -> callbackClient);

            assertThrows(RuntimeException.class, builder::build);

            await().atMost(2, TimeUnit.SECONDS).until(ownedHttpClient::isTerminated);
        } finally {
            callbackClient.close();
        }
    }

    @Test
    void partialBuildFailureClosesTheAlreadyCreatedCallbackClient() throws Exception {
        CallbackClient callbackClient = new CallbackClient(
                new ObjectMapper(), "http://127.0.0.1:1");
        HttpClient ownedHttpClient = httpClientOf(callbackClient);
        NanofaasRuntime.Builder builder = baseBuilder(availablePort(), "partial-build-failure")
                .callbackClientFactory(() -> callbackClient)
                .serverExecutorFactory(() -> {
                    throw new IllegalStateException("executor factory failed");
                });

        assertThrows(IllegalStateException.class, builder::build);

        await().atMost(2, TimeUnit.SECONDS).until(ownedHttpClient::isTerminated);
    }

    @Test
    void stopCancelsAnActiveHandlerAndWaitsForItsPhysicalExit() throws Exception {
        int port = availablePort();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        NanofaasRuntime runtime = NanofaasRuntime.builder()
                .handler(request -> {
                    handlerStarted.countDown();
                    try {
                        releaseHandler.await();
                    } catch (InterruptedException interrupted) {
                        handlerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("ok", true);
                })
                .functionName("active-handler-test")
                .port(port)
                .build();
        Thread startThread = Thread.ofPlatform().start(runtime::start);

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build()) {
            awaitHealth(client, port);
            CompletableFuture<HttpResponse<Void>> invocation = client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/invoke"))
                            .header("Content-Type", "application/json")
                            .header("X-Execution-Id", "active-handler")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(handlerStarted.await(2, TimeUnit.SECONDS));

            runtime.stop();

            assertTrue(handlerInterrupted.await(2, TimeUnit.SECONDS),
                    "runtime-owned handler work must be cancelled during stop");
            await().atMost(2, TimeUnit.SECONDS).until(invocation::isDone);
        } finally {
            releaseHandler.countDown();
            runtime.stop();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void stopIsBoundedWhenAnActiveHandlerIgnoresInterruption() throws Exception {
        int port = availablePort();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        NanofaasRuntime.Builder builder = NanofaasRuntime.builder()
                .handler(request -> {
                    handlerStarted.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            releaseHandler.await();
                            released = true;
                        } catch (InterruptedException _) {
                            // Deliberately non-cooperative: the bounded runtime stop must report/return.
                        }
                    }
                    handlerExited.countDown();
                    return Map.of("ok", true);
                })
                .functionName("bounded-stop-test")
                .port(port);
        configureBuilder(builder, "shutdownTimeout", Duration.class, Duration.ofMillis(100));
        NanofaasRuntime runtime = builder.build();
        Thread startThread = Thread.ofPlatform().start(runtime::start);

        try (HttpClient client = HttpClient.newHttpClient()) {
            awaitHealth(client, port);
            client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/invoke"))
                            .header("Content-Type", "application/json")
                            .header("X-Execution-Id", "non-cooperative")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(handlerStarted.await(2, TimeUnit.SECONDS));
            FutureTask<Void> stop = new FutureTask<>(() -> {
                runtime.stop();
                return null;
            });
            Thread stopThread = Thread.ofPlatform().start(stop);

            stop.get(1, TimeUnit.SECONDS);

            assertFalse(handlerExited.await(50, TimeUnit.MILLISECONDS),
                    "bounded stop must not claim that non-cooperative work exited");
            releaseHandler.countDown();
            assertTrue(handlerExited.await(2, TimeUnit.SECONDS));
            stopThread.join(TimeUnit.SECONDS.toMillis(2));
        } finally {
            releaseHandler.countDown();
            runtime.stop();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void stopPreservesTheCallingThreadsInterruptStatus() throws Exception {
        int port = availablePort();
        NanofaasRuntime runtime = baseBuilder(port, "interrupt-preservation").build();
        Thread startThread = Thread.ofPlatform().start(runtime::start);
        AtomicReference<Boolean> interruptedAfterStop = new AtomicReference<>();

        try (HttpClient client = HttpClient.newHttpClient()) {
            awaitHealth(client, port);
            Thread stopThread = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                runtime.stop();
                interruptedAfterStop.set(Thread.currentThread().isInterrupted());
            });
            stopThread.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(stopThread.isAlive());
            assertTrue(interruptedAfterStop.get());
        } finally {
            runtime.stop();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void stopReleasesTheBlockingStartCaller() throws Exception {
        int port = availablePort();
        NanofaasRuntime runtime = NanofaasRuntime.builder()
                .handler(request -> Map.of("ok", true))
                .functionName("ownership-test")
                .port(port)
                .build();
        CountDownLatch startReturned = new CountDownLatch(1);
        Thread startThread = Thread.ofPlatform().start(() -> {
            try {
                runtime.start();
            } finally {
                startReturned.countDown();
            }
        });

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build()) {
            awaitHealth(client, port);

            runtime.stop();

            assertTrue(startReturned.await(2, TimeUnit.SECONDS),
                    "stop must release the caller blocked in start");
            assertFalse(startThread.isAlive());
        } finally {
            runtime.stop();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void interruptingTheBlockingStartCallerStopsOwnedResourcesAndPreservesInterrupt() throws Exception {
        int port = availablePort();
        ExecutorService executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "p18-interrupted-start-http"));
        NanofaasRuntime runtime = baseBuilder(port, "interrupted-start")
                .serverExecutorFactory(() -> executor)
                .build();
        AtomicReference<Boolean> interruptPreserved = new AtomicReference<>();
        Thread startThread = Thread.ofPlatform().start(() -> {
            runtime.start();
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });

        try (HttpClient client = HttpClient.newHttpClient()) {
            awaitHealth(client, port);

            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(startThread.isAlive());
            assertTrue(interruptPreserved.get());
            await().atMost(2, TimeUnit.SECONDS).until(executor::isTerminated);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThrows(IOException.class, () -> client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())));
        } finally {
            runtime.stop();
            executor.shutdownNow();
            startThread.interrupt();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static NanofaasRuntime.Builder baseBuilder(int port, String functionName) {
        return NanofaasRuntime.builder()
                .handler(request -> Map.of("ok", true))
                .functionName(functionName)
                .port(port);
    }

    private static void configureBuilder(NanofaasRuntime.Builder builder, String methodName,
                                         Class<?> parameterType, Object value) throws Exception {
        Method method = builder.getClass().getDeclaredMethod(methodName, parameterType);
        method.setAccessible(true);
        method.invoke(builder, value);
    }

    private static void awaitHealth(HttpClient client, int port) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
        });
    }

    private static HttpClient httpClientOf(CallbackClient callbackClient) throws Exception {
        Field field = CallbackClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(callbackClient);
    }
}
