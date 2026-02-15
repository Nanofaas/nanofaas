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
import java.util.concurrent.Executors;

public final class NanofaasRuntime {
    private static final Logger log = LoggerFactory.getLogger(NanofaasRuntime.class);

    private final HttpServer server;
    private final int port;
    private final String functionName;

    private NanofaasRuntime(HttpServer server, int port, String functionName) {
        this.server = server;
        this.port = port;
        this.functionName = functionName;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the server, registers a shutdown hook, and blocks the calling thread.
     */
    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down nanofaas-lite runtime for function '{}'", functionName);
            server.stop(5);
        }));

        server.start();
        log.info("nanofaas-lite runtime started on port {} for function '{}'", port, functionName);

        // Block main thread
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Main thread interrupted, shutting down");
        }
    }

    /**
     * Stops the server (for testing).
     */
    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return port;
    }

    public static final class Builder {
        private FunctionHandler handler;
        private int port = 8080;
        private String functionName;

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
            CallbackClient callbackClient = new CallbackClient(objectMapper, callbackUrl);
            RuntimeMetrics metrics = new RuntimeMetrics(effectiveName);

            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
                server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
                server.createContext("/invoke", new InvokeHandler(handler, callbackClient, metrics, objectMapper, effectiveName));
                server.createContext("/health", new HealthHandler());
                server.createContext("/metrics", new MetricsHandler(metrics.getRegistry()));

                return new NanofaasRuntime(server, port, effectiveName);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create HTTP server on port " + port, e);
            }
        }
    }
}
