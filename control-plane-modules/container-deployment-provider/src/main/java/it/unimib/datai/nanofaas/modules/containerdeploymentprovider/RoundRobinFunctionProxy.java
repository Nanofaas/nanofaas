package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RoundRobinFunctionProxy implements ManagedFunctionProxy {

    private static final byte[] NO_BACKENDS = "No ready container backends".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    private final String bindHost;
    private final AtomicReference<List<String>> backends = new AtomicReference<>(List.of());
    private final AtomicInteger counter = new AtomicInteger();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public RoundRobinFunctionProxy(String bindHost) {
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        try {
            this.server = HttpServer.create(new InetSocketAddress(this.bindHost, 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start local function proxy", e);
        }
        server.createContext("/invoke", this::handleInvoke);
        server.createContext("/health", this::handleHealth);
        server.start();
    }

    @Override
    public String endpointUrl() {
        return "http://" + bindHost + ":" + server.getAddress().getPort() + "/invoke";
    }

    @Override
    public void updateBackends(List<String> backendBaseUrls) {
        backends.set(backendBaseUrls == null ? List.of() : List.copyOf(backendBaseUrls));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleInvoke(HttpExchange exchange) throws IOException {
        List<String> currentBackends = backends.get();
        if (currentBackends.isEmpty()) {
            exchange.sendResponseHeaders(503, NO_BACKENDS.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(NO_BACKENDS);
            }
            return;
        }

        String backend = selectBackend(currentBackends);
        URI target = URI.create(backend + exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
        byte[] requestBody = exchange.getRequestBody().readAllBytes();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30))
                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
        copyRequestHeaders(exchange, requestBuilder);

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            copyResponseHeaders(response, exchange);
            exchange.sendResponseHeaders(response.statusCode(), body.length == 0 ? -1 : body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                if (body.length > 0) {
                    outputStream.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            byte[] body = "Interrupted while proxying request".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        } catch (Exception e) {
            byte[] body = ("Proxy error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        byte[] body = backends.get().isEmpty() ? "DOWN".getBytes(StandardCharsets.UTF_8) : "UP".getBytes(StandardCharsets.UTF_8);
        int status = backends.get().isEmpty() ? 503 : 200;
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private String selectBackend(List<String> currentBackends) {
        int index = Math.floorMod(counter.getAndIncrement(), currentBackends.size());
        return currentBackends.get(index);
    }

    private static void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder builder) {
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if ("host".equalsIgnoreCase(entry.getKey())
                    || "content-length".equalsIgnoreCase(entry.getKey())
                    || "connection".equalsIgnoreCase(entry.getKey())
                    || "upgrade".equalsIgnoreCase(entry.getKey())
                    || "http2-settings".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }
    }

    private static void copyResponseHeaders(HttpResponse<byte[]> response, HttpExchange exchange) {
        for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
            if ("content-length".equalsIgnoreCase(entry.getKey())
                    || "connection".equalsIgnoreCase(entry.getKey())
                    || "transfer-encoding".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            exchange.getResponseHeaders().put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }
}
