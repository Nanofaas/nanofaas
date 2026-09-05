package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-function HTTP proxy that round-robins invocation requests across the ready container
 * backends of a managed deployment.
 *
 * <p>The underlying {@link HttpServer} runs on an explicit virtual-thread executor so that
 * concurrent invocations are forwarded concurrently instead of being serialized on the server's
 * dispatcher thread (the original defect: with the default executor every exchange is handled
 * inline, one at a time). A non-blocking admission bound caps the number of invocation requests
 * that are actually being forwarded; a request that arrives when the bound is exhausted is
 * rejected immediately with a defined {@code 503} response — it is never parked on a semaphore.
 * The {@code /health} context is served on the same virtual-thread executor but does not consume
 * invocation admission permits.
 *
 * <p>The per-hop timeout is not hard-coded: it is the function's configured timeout, pushed by
 * the deployment provider at provision time and whenever the function/replica set is updated
 * ({@link #updateLimits(int, Duration)}). The fixed 30&nbsp;s value used to make a function with a
 * longer timeout fail early on this hop while the caller was still within budget.
 */
public final class RoundRobinFunctionProxy implements ManagedFunctionProxy {

    /**
     * Pre-configuration state used until the deployment provider pushes the function's tuning.
     * {@code 4} mirrors the platform default concurrency and {@code 30 s} mirrors the platform
     * default function timeout ({@code nanofaas.defaults.timeoutMs}); neither value ever governs a
     * serving proxy in production because the provider calls {@link #updateLimits(int, Duration)}
     * before the proxy's endpoint is published.
     */
    static final int DEFAULT_MAX_IN_FLIGHT = 4;
    static final Duration DEFAULT_SINGLE_HOP_TIMEOUT = Duration.ofSeconds(30);

    private static final byte[] NO_BACKENDS = "No ready container backends".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BUSY = "Too many concurrent invocations".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLOSED = "Proxy is shutting down".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UP = "UP".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOWN = "DOWN".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERRUPTED = "Interrupted while proxying request".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROXY_TIMEOUT = "Proxy timed out forwarding the invocation".getBytes(StandardCharsets.UTF_8);

    private final String bindHost;
    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final AtomicReference<List<String>> backends = new AtomicReference<>(List.of());
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger(DEFAULT_MAX_IN_FLIGHT);
    private final AtomicReference<Duration> singleHopTimeout = new AtomicReference<>(DEFAULT_SINGLE_HOP_TIMEOUT);
    private final AtomicBoolean closed = new AtomicBoolean();

    public RoundRobinFunctionProxy(String bindHost) {
        this(bindHost, DEFAULT_MAX_IN_FLIGHT, DEFAULT_SINGLE_HOP_TIMEOUT);
    }

    RoundRobinFunctionProxy(String bindHost, int maxInFlight, Duration singleHopTimeout) {
        this(bindHost, maxInFlight, singleHopTimeout, null);
    }

    RoundRobinFunctionProxy(String bindHost, int maxInFlight, Duration singleHopTimeout, HttpClient httpClient) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be >= 1, was " + maxInFlight);
        }
        if (singleHopTimeout == null || singleHopTimeout.isZero() || singleHopTimeout.isNegative()) {
            throw new IllegalArgumentException("singleHopTimeout must be a positive duration, was " + singleHopTimeout);
        }
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        this.maxInFlight.set(maxInFlight);
        this.singleHopTimeout.set(singleHopTimeout);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                : httpClient;
        try {
            this.server = HttpServer.create(new InetSocketAddress(this.bindHost, 0), 0);
        } catch (IOException e) {
            this.executor.close();
            this.httpClient.close();
            throw new IllegalStateException("Unable to start local function proxy", e);
        }
        server.setExecutor(executor);
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
    public void updateLimits(int maxInFlight, Duration singleHopTimeout) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be >= 1, was " + maxInFlight);
        }
        if (singleHopTimeout == null || singleHopTimeout.isZero() || singleHopTimeout.isNegative()) {
            throw new IllegalArgumentException("singleHopTimeout must be a positive duration, was " + singleHopTimeout);
        }
        this.maxInFlight.set(maxInFlight);
        this.singleHopTimeout.set(singleHopTimeout);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Stop accepting new exchanges first, then interrupt in-flight handler virtual threads
        // (blocked in httpClient.send) and release the client's own resources. HttpServer.stop
        // returns promptly and does not shut down a caller-provided executor.
        try {
            server.stop(0);
        } finally {
            executor.shutdownNow();
            httpClient.close();
        }
    }

    private void handleInvoke(HttpExchange exchange) {
        try {
            if (closed.get()) {
                send(exchange, 503, CLOSED);
                return;
            }
            List<String> currentBackends = backends.get();
            if (currentBackends.isEmpty()) {
                send(exchange, 503, NO_BACKENDS);
                return;
            }
            if (!tryAcquireInFlight()) {
                send(exchange, 503, BUSY);
                return;
            }
            try {
                forward(exchange, selectBackend(currentBackends));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 500, INTERRUPTED);
            } catch (HttpConnectTimeoutException e) {
                send(exchange, 502, proxyError(e));
            } catch (HttpTimeoutException e) {
                send(exchange, 504, PROXY_TIMEOUT);
            } catch (IOException | RuntimeException e) {
                send(exchange, 502, proxyError(e));
            } finally {
                inFlight.decrementAndGet();
            }
        } finally {
            exchange.close();
        }
    }

    private void handleHealth(HttpExchange exchange) {
        try {
            boolean healthy = !closed.get() && !backends.get().isEmpty();
            send(exchange, healthy ? 200 : 503, healthy ? UP : DOWN);
        } finally {
            exchange.close();
        }
    }

    /**
     * Non-blocking admission: takes an in-flight permit only if the invocation bound is not
     * exhausted. Never blocks. A request that fails admission is answered with a defined
     * {@code 503} by the caller instead of waiting for a permit.
     */
    private boolean tryAcquireInFlight() {
        int current;
        do {
            current = inFlight.get();
            if (current >= maxInFlight.get()) {
                return false;
            }
        } while (!inFlight.compareAndSet(current, current + 1));
        return true;
    }

    private void forward(HttpExchange exchange, String backend) throws IOException, InterruptedException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        URI target = URI.create(backend + exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                .timeout(singleHopTimeout.get())
                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
        copyRequestHeaders(exchange, requestBuilder);

        HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        writeBackendResponse(exchange, response);
    }

    private String selectBackend(List<String> currentBackends) {
        int index = Math.floorMod(counter.getAndIncrement(), currentBackends.size());
        return currentBackends.get(index);
    }

    private static void writeBackendResponse(HttpExchange exchange, HttpResponse<byte[]> response)
            throws IOException {
        byte[] body = response.body();
        copyResponseHeaders(response, exchange);
        exchange.sendResponseHeaders(response.statusCode(), body.length == 0 ? -1 : body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            if (body.length > 0) {
                outputStream.write(body);
            }
        }
    }

    /**
     * Best-effort error response. Writing to a caller that already disconnected must not escape:
     * at that point there is nobody left to answer and the exchange is closed by the caller of
     * this method.
     */
    private static void send(HttpExchange exchange, int status, byte[] body) {
        try {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                if (body.length > 0) {
                    outputStream.write(body);
                }
            }
        } catch (IOException _) {
            exchange.close();
        }
    }

    private static byte[] proxyError(Exception e) {
        return ("Proxy error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
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
