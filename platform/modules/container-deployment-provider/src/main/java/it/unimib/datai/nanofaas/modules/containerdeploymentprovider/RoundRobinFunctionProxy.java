package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final byte[] REQUEST_TIMEOUT = "Proxy timed out reading the request body".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REQUEST_TOO_LARGE = "Proxy request body exceeds the configured limit".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE_TOO_LARGE = "Proxy response body exceeds the configured limit".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BUFFER_CAPACITY_EXHAUSTED = "Proxy buffer capacity exhausted".getBytes(StandardCharsets.UTF_8);

    private final String bindHost;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor deadlineExecutor;
    private final HttpClient httpClient;
    private final ContainerProxyProperties proxyProperties;
    private final BufferBudget bufferBudget;
    private final DeadlineFactory deadlineFactory;
    private final ResponseBodyWriter responseBodyWriter;
    private final java.util.Set<HttpExchange> activeExchanges = ConcurrentHashMap.newKeySet();
    private final AtomicReference<List<String>> backends = new AtomicReference<>(List.of());
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger(DEFAULT_MAX_IN_FLIGHT);
    private final AtomicReference<Duration> singleHopTimeout = new AtomicReference<>(DEFAULT_SINGLE_HOP_TIMEOUT);
    /** Admission gate: flipped by the first {@link #close()}, before any resource is touched. */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Set only once every close stage has actually completed, so a failed close stays retryable. */
    private final AtomicBoolean released = new AtomicBoolean();

    public RoundRobinFunctionProxy(String bindHost) {
        this(bindHost, DEFAULT_MAX_IN_FLIGHT, DEFAULT_SINGLE_HOP_TIMEOUT);
    }

    RoundRobinFunctionProxy(String bindHost, ContainerProxyProperties proxyProperties) {
        this(bindHost, DEFAULT_MAX_IN_FLIGHT, DEFAULT_SINGLE_HOP_TIMEOUT, null, proxyProperties);
    }

    RoundRobinFunctionProxy(String bindHost, int maxInFlight, Duration singleHopTimeout) {
        this(bindHost, maxInFlight, singleHopTimeout, null);
    }

    RoundRobinFunctionProxy(String bindHost, int maxInFlight, Duration singleHopTimeout, HttpClient httpClient) {
        this(bindHost, maxInFlight, singleHopTimeout, httpClient, ContainerProxyProperties.defaults());
    }

    RoundRobinFunctionProxy(String bindHost,
                            int maxInFlight,
                            Duration singleHopTimeout,
                            HttpClient httpClient,
                            ContainerProxyProperties proxyProperties) {
        this(bindHost, maxInFlight, singleHopTimeout, httpClient, proxyProperties, null,
                RoundRobinFunctionProxy::writeResponseBody);
    }

    RoundRobinFunctionProxy(String bindHost,
                            int maxInFlight,
                            Duration singleHopTimeout,
                            HttpClient httpClient,
                            ContainerProxyProperties proxyProperties,
                            DeadlineFactory deadlineFactory,
                            ResponseBodyWriter responseBodyWriter) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be >= 1, was " + maxInFlight);
        }
        if (singleHopTimeout == null || singleHopTimeout.isZero() || singleHopTimeout.isNegative()) {
            throw new IllegalArgumentException("singleHopTimeout must be a positive duration, was " + singleHopTimeout);
        }
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        this.proxyProperties = java.util.Objects.requireNonNull(proxyProperties, "proxyProperties");
        this.bufferBudget = new BufferBudget(proxyProperties.maxBufferedBytes());
        this.maxInFlight.set(maxInFlight);
        this.singleHopTimeout.set(singleHopTimeout);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.deadlineExecutor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
                .name("container-proxy-deadline-", 0)
                .daemon(true)
                .factory());
        this.deadlineExecutor.setRemoveOnCancelPolicy(true);
        this.deadlineExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.deadlineFactory = deadlineFactory == null ? this::newPhaseDeadline : deadlineFactory;
        this.responseBodyWriter = java.util.Objects.requireNonNull(responseBodyWriter, "responseBodyWriter");
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                : httpClient;
        try {
            this.server = HttpServer.create(new InetSocketAddress(this.bindHost, 0), 0);
        } catch (IOException e) {
            this.executor.close();
            this.deadlineExecutor.shutdownNow();
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

    /**
     * Releases the proxy: the listening socket, the handler threads and the HTTP client.
     *
     * <p>The admission gate flips first and unconditionally, so from this instant the proxy answers
     * {@code 503} even if a later stage fails — a deployment being removed never keeps admitting
     * traffic because its teardown was incomplete. Then each stage runs even when an earlier one
     * throws: this is the only path that reclaims these resources, and a stage skipped here has no
     * other owner left to run it. Stop accepting new exchanges, interrupt the in-flight handler
     * virtual threads (blocked in {@code httpClient.send}), release the client.
     *
     * <p>A stage that fails leaves the proxy <em>not</em> released and rethrows, so the owner still
     * holds a handle it can close again; every stage is idempotent, so the retry simply re-runs
     * them. A close that succeeded is a no-op on any later call.
     */
    @Override
    public void close() {
        closed.set(true);
        if (released.get()) {
            return;
        }
        RuntimeException failure = releaseStage(null, () -> server.stop(0));
        failure = releaseStage(failure, () -> activeExchanges.forEach(HttpExchange::close));
        failure = releaseStage(failure, executor::shutdownNow);
        failure = releaseStage(failure, deadlineExecutor::shutdownNow);
        failure = releaseStage(failure, httpClient::close);
        failure = releaseStage(failure, executor::close);
        failure = releaseStage(failure, deadlineExecutor::close);
        if (failure != null) {
            throw failure;
        }
        released.set(true);
    }

    Snapshot snapshot() {
        return new Snapshot(inFlight.get(), bufferBudget.used());
    }

    record Snapshot(int inFlight, long bufferedBytes) {
    }

    interface DeadlineFactory {
        Deadline start(Duration duration, Runnable abort);
    }

    interface Deadline extends AutoCloseable {
        boolean expired();

        @Override
        void close();
    }

    @FunctionalInterface
    interface ResponseBodyWriter {
        void write(HttpExchange exchange, byte[] bytes, int length) throws IOException;
    }

    private static RuntimeException releaseStage(RuntimeException failure, Runnable stage) {
        try {
            stage.run();
        } catch (RuntimeException stageFailure) {
            if (failure == null) {
                return stageFailure;
            }
            failure.addSuppressed(stageFailure);
        }
        return failure;
    }

    private void handleInvoke(HttpExchange exchange) {
        activeExchanges.add(exchange);
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
            } catch (InboundReadTimeoutException e) {
                send(exchange, 408, REQUEST_TIMEOUT);
            } catch (BodyLimitExceededException e) {
                send(exchange, e.responseBody ? 502 : 413,
                        e.responseBody ? RESPONSE_TOO_LARGE : REQUEST_TOO_LARGE);
            } catch (BufferCapacityExceededException e) {
                send(exchange, 503, BUFFER_CAPACITY_EXHAUSTED);
            } catch (ResponseWriteTimeoutException e) {
                exchange.close();
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
            activeExchanges.remove(exchange);
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
        BufferedBody requestBody = readInboundBody(exchange);
        try {
            URI target = URI.create(backend + exchange.getRequestURI().getPath()
                    + (exchange.getRequestURI().getRawQuery() == null
                    ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            try (BufferReservation publisherCopy = bufferBudget.reserve(requestBody.length())) {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                        .timeout(singleHopTimeout.get())
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(
                                requestBody.bytes(), 0, requestBody.length()));
                copyRequestHeaders(exchange, requestBuilder);

                BackendResponse backendResponse = readBackendResponse(
                        requestBuilder.build(), requestBody, publisherCopy);
                try (BufferedBody responseBody = backendResponse.body()) {
                    writeBackendResponse(exchange, backendResponse.response(), responseBody);
                }
            }
        } finally {
            requestBody.close();
        }
    }

    private BackendResponse readBackendResponse(HttpRequest request,
                                                BufferedBody requestBody,
                                                BufferReservation publisherCopy)
            throws IOException, InterruptedException {
        AtomicReference<InputStream> responseStream = new AtomicReference<>();
        BufferedBody responseBody = null;
        try (Deadline deadline = deadline(singleHopTimeout.get(), () -> close(responseStream.get()))) {
            try {
                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                responseStream.set(response.body());
                publisherCopy.close();
                requestBody.close();
                responseBody = BufferedBody.read(
                        response.body(), proxyProperties.maxResponseBytes(), bufferBudget, true);
                if (deadline.expired()) {
                    throw new HttpTimeoutException("backend response deadline expired");
                }
                BackendResponse result = new BackendResponse(response, responseBody);
                responseBody = null;
                return result;
            } catch (IOException | InterruptedException e) {
                if (deadline.expired()) {
                    throw new HttpTimeoutException("backend request deadline expired");
                }
                throw e;
            } finally {
                close(responseStream.get());
                if (responseBody != null) {
                    responseBody.close();
                }
            }
        }
    }

    private BufferedBody readInboundBody(HttpExchange exchange) throws IOException {
        try (Deadline deadline = deadline(proxyProperties.inboundReadTimeout(), () -> { })) {
            try {
                BufferedBody body = BufferedBody.read(
                        exchange.getRequestBody(), proxyProperties.maxRequestBytes(), bufferBudget, false);
                if (deadline.expired()) {
                    body.close();
                    throw new InboundReadTimeoutException();
                }
                return body;
            } catch (IOException e) {
                if (deadline.expired()) {
                    throw new InboundReadTimeoutException();
                }
                throw e;
            }
        }
    }

    private String selectBackend(List<String> currentBackends) {
        int index = Math.floorMod(counter.getAndIncrement(), currentBackends.size());
        return currentBackends.get(index);
    }

    private void writeBackendResponse(HttpExchange exchange,
                                      HttpResponse<InputStream> response,
                                      BufferedBody body) throws IOException {
        copyResponseHeaders(response, exchange);
        try (Deadline deadline = deadline(
                proxyProperties.responseWriteTimeout(), exchange::close)) {
            try {
                exchange.sendResponseHeaders(response.statusCode(), body.length() == 0 ? -1 : body.length());
                responseBodyWriter.write(exchange, body.bytes(), body.length());
                if (deadline.expired()) {
                    throw new ResponseWriteTimeoutException();
                }
            } catch (IOException e) {
                if (deadline.expired()) {
                    throw new ResponseWriteTimeoutException();
                }
                throw e;
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

    private Deadline deadline(Duration duration, Runnable abort) {
        return deadlineFactory.start(duration, abort);
    }

    private Deadline newPhaseDeadline(Duration duration, Runnable abort) {
        return new PhaseDeadline(duration, abort);
    }

    private static void writeResponseBody(HttpExchange exchange, byte[] bytes, int length) throws IOException {
        try (OutputStream outputStream = exchange.getResponseBody()) {
            if (length > 0) {
                outputStream.write(bytes, 0, length);
            }
        }
    }

    private static void close(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException _) {
            // Closing is cancellation; the request path reports the primary failure.
        }
    }

    private final class PhaseDeadline implements Deadline {
        private final Thread owner = Thread.currentThread();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean expired = new AtomicBoolean();
        private final ScheduledFuture<?> timeout;

        private PhaseDeadline(Duration duration, Runnable abort) {
            timeout = deadlineExecutor.schedule(() -> {
                if (completed.compareAndSet(false, true)) {
                    expired.set(true);
                    abort.run();
                    owner.interrupt();
                }
            }, duration.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public boolean expired() {
            return expired.get();
        }

        @Override
        public void close() {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false);
            } else if (expired.get()) {
                Thread.interrupted();
            }
        }
    }

    private static final class BufferBudget {
        private final long maximum;
        private final AtomicLong used = new AtomicLong();

        private BufferBudget(long maximum) {
            this.maximum = maximum;
        }

        private int reserveUpTo(int preferredBytes, int minimumBytes)
                throws BufferCapacityExceededException {
            long current;
            do {
                current = used.get();
                long remaining = maximum - current;
                if (remaining < minimumBytes) {
                    throw new BufferCapacityExceededException();
                }
                int reserved = (int) Math.min(preferredBytes, remaining);
                if (used.compareAndSet(current, current + reserved)) {
                    return reserved;
                }
            } while (true);
        }

        private BufferReservation reserve(int bytes) throws BufferCapacityExceededException {
            reserveUpTo(bytes, bytes);
            return new BufferReservation(this, bytes);
        }

        private void release(long bytes) {
            if (bytes != 0) {
                used.addAndGet(-bytes);
            }
        }

        private long used() {
            return used.get();
        }
    }

    private static final class BufferReservation implements AutoCloseable {
        private final BufferBudget budget;
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BufferReservation(BufferBudget budget, long bytes) {
            this.budget = budget;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                budget.release(bytes);
            }
        }
    }

    private static final class BufferedBody implements AutoCloseable {
        private static final byte[] EMPTY = new byte[0];

        private final BufferBudget budget;
        private final AtomicBoolean closed = new AtomicBoolean();
        private byte[] bytes;
        private int length;

        private BufferedBody(BufferBudget budget) {
            this.budget = budget;
            this.bytes = EMPTY;
        }

        private static BufferedBody read(InputStream input,
                                         int maximum,
                                         BufferBudget budget,
                                         boolean responseBody) throws IOException {
            BufferedBody body = new BufferedBody(budget);
            try {
                while (true) {
                    if (body.length == maximum) {
                        if (input.read() < 0) {
                            return body;
                        }
                        throw new BodyLimitExceededException(responseBody);
                    }
                    body.ensureWritable(maximum);
                    int read = input.read(body.bytes, body.length, body.bytes.length - body.length);
                    if (read < 0) {
                        return body;
                    }
                    if (read == 0) {
                        continue;
                    }
                    body.length += read;
                }
            } catch (IOException | RuntimeException | Error failure) {
                body.close();
                throw failure;
            }
        }

        private void ensureWritable(int maximum) throws BufferCapacityExceededException {
            if (length < bytes.length) {
                return;
            }
            int preferredLength = bytes.length == 0
                    ? Math.min(8192, maximum)
                    : Math.min(maximum, Math.multiplyExact(bytes.length, 2));
            int nextLength = budget.reserveUpTo(preferredLength, bytes.length + 1);
            byte[] previous = bytes;
            try {
                bytes = java.util.Arrays.copyOf(previous, nextLength);
            } catch (RuntimeException | Error allocationFailure) {
                budget.release(nextLength);
                throw allocationFailure;
            }
            budget.release(previous.length);
        }

        private byte[] bytes() {
            return bytes;
        }

        private int length() {
            return length;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                budget.release(bytes.length);
                bytes = EMPTY;
                length = 0;
            }
        }
    }

    private static final class BodyLimitExceededException extends IOException {
        private final boolean responseBody;

        private BodyLimitExceededException(boolean responseBody) {
            super(responseBody ? "response body exceeds configured limit" : "request body exceeds configured limit");
            this.responseBody = responseBody;
        }
    }

    private static final class BufferCapacityExceededException extends IOException {
        private BufferCapacityExceededException() {
            super("aggregate proxy buffer capacity exhausted");
        }
    }

    private static final class InboundReadTimeoutException extends IOException {
    }

    private static final class ResponseWriteTimeoutException extends IOException {
    }

    private record BackendResponse(HttpResponse<InputStream> response, BufferedBody body) {
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

    private static void copyResponseHeaders(HttpResponse<?> response, HttpExchange exchange) {
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
