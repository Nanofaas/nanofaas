package it.unimib.datai.nanofaas.sdk.lite;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.handler.HealthHandler;
import it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandler;
import it.unimib.datai.nanofaas.sdk.lite.handler.MetricsHandler;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class NanofaasRuntime {
    private static final Logger log = LoggerFactory.getLogger(NanofaasRuntime.class);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final HttpServer server;
    private final int port;
    private final String functionName;
    private final InvokeHandler invokeHandler;
    private final CallbackClient callbackClient;
    private final ExecutorService serverExecutor;
    private final boolean ownsServerExecutor;
    private final Duration shutdownTimeout;
    private final ShutdownHooks shutdownHooks;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Object lifecycleMonitor = new Object();
    private boolean started;
    private boolean stopping;
    private boolean shutdownHookRegistered;
    private final Thread shutdownHook;

    private NanofaasRuntime(HttpServer server, int port, String functionName, InvokeHandler invokeHandler,
                            CallbackClient callbackClient, ExecutorService serverExecutor,
                            boolean ownsServerExecutor, Duration shutdownTimeout,
                            ShutdownHooks shutdownHooks) {
        this.server = server;
        this.port = port;
        this.functionName = functionName;
        this.invokeHandler = invokeHandler;
        this.callbackClient = callbackClient;
        this.serverExecutor = serverExecutor;
        this.ownsServerExecutor = ownsServerExecutor;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownHooks = shutdownHooks;
        this.shutdownHook = new Thread(() -> {
            log.info("Shutting down nanofaas-lite runtime for function '{}'", functionName);
            stop();
        }, "nanofaas-lite-shutdown-" + functionName);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the server, registers a shutdown hook, and blocks the calling thread.
     */
    public void start() {
        RuntimeException startupFailure = null;
        synchronized (lifecycleMonitor) {
            if (started || stopping) {
                throw new IllegalStateException("Runtime has already been started or stopped");
            }
            started = true;
            try {
                shutdownHooks.add(shutdownHook);
                shutdownHookRegistered = true;
                server.start();
            } catch (RuntimeException ex) {
                startupFailure = ex;
            }
        }
        if (startupFailure != null) {
            stop();
            throw startupFailure;
        }
        log.info("nanofaas-lite runtime started on port {} for function '{}'", port, functionName);

        try {
            stopped.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.info("Main thread interrupted, shutting down");
            stop();
        }
    }

    /**
     * Stops the server (for testing).
     */
    public void stop() {
        boolean cleanupOwner;
        synchronized (lifecycleMonitor) {
            cleanupOwner = !stopping;
            if (cleanupOwner) {
                stopping = true;
            }
        }
        if (!cleanupOwner) {
            awaitStopped();
            return;
        }
        boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        try {
            invokeHandler.beginStop();
            server.stop(0);
            if (!invokeHandler.shutdown(remaining(deadline))) {
                log.warn("Shutdown deadline reached with physical work still active for function '{}'",
                        functionName);
            }
            callbackClient.close(remaining(deadline));
            if (ownsServerExecutor) {
                shutdownExecutor(serverExecutor, deadline);
            }
        } finally {
            removeShutdownHook();
            stopped.countDown();
            if (interruptedOnEntry) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitStopped() {
        try {
            stopped.await(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    private static void shutdownExecutor(ExecutorService executor, long deadlineNanos) {
        executor.shutdown();
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void removeShutdownHook() {
        synchronized (lifecycleMonitor) {
            if (!shutdownHookRegistered) {
                return;
            }
            shutdownHookRegistered = false;
            if (Thread.currentThread() == shutdownHook) {
                return;
            }
        }
        try {
            shutdownHooks.remove(shutdownHook);
        } catch (IllegalStateException _) {
            // JVM shutdown is already in progress.
        }
    }

    public int getPort() {
        return port;
    }

    public static final class Builder {
        private FunctionHandler handler;
        private int port = 8080;
        private String functionName;
        private Supplier<CallbackClient> callbackClientFactory;
        private Supplier<ExecutorService> serverExecutorFactory = Executors::newVirtualThreadPerTaskExecutor;
        private ExecutorService injectedServerExecutor;
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        private ShutdownHooks shutdownHooks = ShutdownHooks.jvm();

        private Builder() {}

        public Builder handler(FunctionHandler handler) {
            this.handler = handler;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder functionName(String functionName) {
            this.functionName = functionName;
            return this;
        }

        Builder callbackClientFactory(Supplier<CallbackClient> callbackClientFactory) {
            this.callbackClientFactory = callbackClientFactory;
            return this;
        }

        Builder serverExecutorFactory(Supplier<ExecutorService> serverExecutorFactory) {
            this.serverExecutorFactory = serverExecutorFactory;
            this.injectedServerExecutor = null;
            return this;
        }

        Builder serverExecutor(ExecutorService serverExecutor) {
            this.injectedServerExecutor = serverExecutor;
            return this;
        }

        Builder shutdownHooks(ShutdownHooks shutdownHooks) {
            this.shutdownHooks = shutdownHooks;
            return this;
        }

        /**
         * Sets the maximum total time spent draining runtime-owned work and resources.
         */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            if (shutdownTimeout == null || shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
                throw new IllegalArgumentException("shutdownTimeout must be positive");
            }
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        public NanofaasRuntime build() {
            if (handler == null) {
                throw new IllegalStateException("FunctionHandler must be set");
            }

            String effectiveName = functionName;
            if (effectiveName == null || effectiveName.isBlank()) {
                effectiveName = System.getenv("FUNCTION_NAME");
            }
            if (effectiveName == null || effectiveName.isBlank()) {
                effectiveName = "unknown";
            }

            ObjectMapper objectMapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String callbackUrl = System.getenv("CALLBACK_URL");
            CallbackClient callbackClient = callbackClientFactory == null
                    ? new CallbackClient(objectMapper, callbackUrl)
                    : callbackClientFactory.get();
            RuntimeMetrics metrics = new RuntimeMetrics(effectiveName);
            boolean ownsServerExecutor = injectedServerExecutor == null;
            ExecutorService serverExecutor = null;
            HttpServer server = null;
            InvokeHandler invokeHandler = null;
            try {
                serverExecutor = ownsServerExecutor
                        ? serverExecutorFactory.get()
                        : injectedServerExecutor;
                server = HttpServer.create(new InetSocketAddress(port), 0);
                server.setExecutor(serverExecutor);
                invokeHandler = new InvokeHandler(
                        handler, callbackClient, metrics, objectMapper, effectiveName);
                server.createContext("/invoke", invokeHandler);
                server.createContext("/health", new HealthHandler());
                server.createContext("/metrics", new MetricsHandler(metrics.getRegistry()));

                return new NanofaasRuntime(server, port, effectiveName, invokeHandler, callbackClient,
                        serverExecutor, ownsServerExecutor, shutdownTimeout, shutdownHooks);
            } catch (IOException e) {
                cleanupPartialBuild(server, invokeHandler, callbackClient, serverExecutor,
                        ownsServerExecutor, shutdownTimeout);
                throw new RuntimeException("Failed to create HTTP server on port " + port, e);
            } catch (RuntimeException e) {
                cleanupPartialBuild(server, invokeHandler, callbackClient, serverExecutor,
                        ownsServerExecutor, shutdownTimeout);
                throw e;
            }
        }

        private static void cleanupPartialBuild(HttpServer server, InvokeHandler invokeHandler,
                                                CallbackClient callbackClient,
                                                ExecutorService serverExecutor,
                                                boolean ownsServerExecutor,
                                                Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            if (server != null) {
                server.stop(0);
            }
            if (invokeHandler != null) {
                invokeHandler.shutdown(remaining(deadline));
            }
            callbackClient.close(remaining(deadline));
            if (ownsServerExecutor && serverExecutor != null) {
                shutdownExecutor(serverExecutor, deadline);
            }
        }
    }

    interface ShutdownHooks {
        void add(Thread hook);

        void remove(Thread hook);

        static ShutdownHooks jvm() {
            return new ShutdownHooks() {
                @Override
                public void add(Thread hook) {
                    Runtime.getRuntime().addShutdownHook(hook);
                }

                @Override
                public void remove(Thread hook) {
                    Runtime.getRuntime().removeShutdownHook(hook);
                }
            };
        }
    }
}
