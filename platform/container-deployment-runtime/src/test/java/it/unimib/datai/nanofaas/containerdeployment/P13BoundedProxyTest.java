package it.unimib.datai.nanofaas.containerdeployment;



import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class P13BoundedProxyTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);
    private static final Duration PHASE_TIMEOUT = Duration.ofMillis(150);

    private final List<HttpServer> backends = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private RoundRobinFunctionProxy proxy;

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.close();
        }
        backends.forEach(server -> server.stop(0));
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void fixedLengthBodyOverPerRequestLimitIsRejectedBeforeDispatchAndReleasesOwnership() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        HttpServer backend = backend(exchange -> {
            backendCalls.incrementAndGet();
            respond(exchange, 200, "unexpected".getBytes(StandardCharsets.UTF_8));
        });
        proxy = proxy(limits(16, 64, 128));
        proxy.updateBackends(List.of(baseUrl(backend)));

        HttpResponse<String> response = post(new byte[17]);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(backendCalls).hasValue(0);
        assertIdle();
    }

    @Test
    void chunkedBodyWithoutContentLengthIsBoundedByBytesActuallyReceived() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        HttpServer backend = backend(exchange -> {
            backendCalls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        proxy = proxy(limits(16, 64, 128));
        proxy.updateBackends(List.of(baseUrl(backend)));

        try (Socket socket = connect()) {
            OutputStream output = socket.getOutputStream();
            output.write(("POST /invoke HTTP/1.1\r\n" // NOSONAR (java:S6126): raw HTTP needs explicit CRLF line endings
                    + "Host: 127.0.0.1\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n"
                    + "9\r\n123456789\r\n"
                    + "8\r\nabcdefgh\r\n"
                    + "0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            assertThat(readStatus(socket)).isEqualTo(413);
        }
        assertThat(backendCalls).hasValue(0);
        awaitIdle();
        assertIdle();
    }

    @Test
    void inboundDeadlineStartsBeforeTheWholeBodyArrives() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        HttpServer backend = backend(exchange -> {
            backendCalls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        proxy = proxy(new ProxySettings(64, 64, 128, PHASE_TIMEOUT, Duration.ofSeconds(2)));
        proxy.updateBackends(List.of(baseUrl(backend)));

        try (Socket socket = connect()) {
            OutputStream output = socket.getOutputStream();
            output.write(("POST /invoke HTTP/1.1\r\n" // NOSONAR (java:S6126): raw HTTP needs explicit CRLF line endings
                    + "Host: 127.0.0.1\r\n"
                    + "Content-Length: 4\r\n\r\n"
                    + "x").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            assertThat(readStatusOrClosed(socket)).isIn(-1, 408);
        }
        assertThat(backendCalls).hasValue(0);
        awaitIdle();
        assertIdle();
    }

    @Test
    void oversizedBackendResponseIsCancelledAndReturnsControlled502() throws Exception {
        CountDownLatch backendCancelled = new CountDownLatch(1);
        HttpServer backend = backend(exchange -> {
            exchange.sendResponseHeaders(200, 16 * 1024 * 1024);
            try (OutputStream output = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                while (true) {
                    output.write(chunk);
                    output.flush();
                }
            } catch (IOException _) {
                backendCancelled.countDown();
            }
        });
        proxy = proxy(limits(64, 16, 128));
        proxy.updateBackends(List.of(baseUrl(backend)));

        HttpResponse<String> response = post("ok".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("response body exceeds");
        assertThat(backendCancelled.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertIdle();
    }

    @Test
    void aggregateBufferBudgetAccountsForOriginalRequestArrayAndPublisherCopy() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer backend = backend(exchange -> {
            firstArrived.countDown();
            await(release);
            respond(exchange, 200, "x".getBytes(StandardCharsets.UTF_8));
        });
        proxy = proxy(limits(16, 1, 33));
        proxy.updateBackends(List.of(baseUrl(backend)));
        HttpClient client = HttpClient.newHttpClient();

        CompletableFuture<HttpResponse<String>> admitted = postAsync(client, new byte[16]);
        assertThat(firstArrived.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(proxy.snapshot().bufferedBytes()).isEqualTo(32);
        assertThat(proxy.snapshot().inFlight()).isEqualTo(1);

        HttpResponse<String> rejected = post(new byte[1]);
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(proxy.snapshot().bufferedBytes()).isLessThanOrEqualTo(33);

        release.countDown();
        assertThat(admitted.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
        assertIdle();
    }

    @Test
    void requestGraphOwnersRemainReservedWhileCallerResponseWriteIsBlocked() throws Exception {
        byte[] responseBody = "r".getBytes(StandardCharsets.UTF_8);
        HttpServer backend = backend(exchange -> respond(exchange, 200, responseBody));
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        RoundRobinFunctionProxy.ResponseBodyWriter blockedWriter = (exchange, bytes, length) -> {
            writeStarted.countDown();
            try {
                if (!releaseWrite.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("controlled response writer release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("controlled response writer interrupted", e);
            }
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes, 0, length);
            }
        };
        proxy = new RoundRobinFunctionProxy(
                "127.0.0.1", 4, Duration.ofSeconds(30), HttpClient.newHttpClient(),
                new ProxySettings(
                        16, 1, 33, Duration.ofSeconds(2), Duration.ofSeconds(30)),
                null, blockedWriter);
        proxy.updateBackends(List.of(baseUrl(backend)));

        CompletableFuture<HttpResponse<String>> call =
                postAsync(HttpClient.newHttpClient(), new byte[16]);
        assertThat(writeStarted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        assertThat(proxy.snapshot()).isEqualTo(new RoundRobinFunctionProxy.Snapshot(1, 33));

        releaseWrite.countDown();
        HttpResponse<String> response = call.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("r");
        assertIdle();
    }

    @Test
    void aggregateBudgetSmallerThanGrowthChunkStillAcceptsARepresentableBody() throws Exception {
        HttpServer backend = backend(exchange -> respond(exchange, 204, new byte[0]));
        proxy = proxy(new ProxySettings(
                3, 64, 7, Duration.ofSeconds(2), Duration.ofSeconds(2)));
        proxy.updateBackends(List.of(baseUrl(backend)));

        HttpResponse<String> response = post(new byte[3]);

        assertThat(response.statusCode()).isEqualTo(204);
        assertIdle();
    }

    @Test
    void backendDeadlineIncludesResponseBodyAndReleasesAllOwnership() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch neverRelease = new CountDownLatch(1);
        HttpServer backend = backend(exchange -> {
            exchange.sendResponseHeaders(200, 4);
            responseStarted.countDown();
            await(neverRelease);
        });
        proxy = new RoundRobinFunctionProxy(
                "127.0.0.1", 2, PHASE_TIMEOUT, HttpClient.newHttpClient(), limits(64, 64, 128));
        proxy.updateBackends(List.of(baseUrl(backend)));

        CompletableFuture<HttpResponse<String>> call = postAsync(HttpClient.newHttpClient(), new byte[1]);
        assertThat(responseStarted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        HttpResponse<String> response = call.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(response.statusCode()).isEqualTo(504);
        assertIdle();
    }

    @Test
    void callerWriteStartsAfterBackendDeadlineClosesAndUsesItsOwnControlledDeadline() throws Exception {
        CountDownLatch backendCompleted = new CountDownLatch(1);
        HttpServer backend = backend(exchange -> {
            respond(exchange, 200, "response".getBytes(StandardCharsets.UTF_8));
            backendCompleted.countDown();
        });
        ControlledDeadlineFactory deadlines = new ControlledDeadlineFactory();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch writerInterrupted = new CountDownLatch(1);
        RoundRobinFunctionProxy.ResponseBodyWriter blockedWriter = (exchange, bytes, length) -> {
            writeStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                writerInterrupted.countDown();
                throw new IOException("controlled response writer interrupted", e);
            }
        };
        Duration backendDeadline = Duration.ofSeconds(1);
        Duration responseWriteDeadline = Duration.ofSeconds(2);
        proxy = new RoundRobinFunctionProxy(
                "127.0.0.1", 4, backendDeadline, HttpClient.newHttpClient(),
                new ProxySettings(
                        64, 64, 128, Duration.ofSeconds(2), responseWriteDeadline),
                deadlines, blockedWriter);
        proxy.updateBackends(List.of(baseUrl(backend)));

        CompletableFuture<HttpResponse<String>> call = postAsync(HttpClient.newHttpClient(), new byte[0]);
        ControlledDeadline inbound = deadlines.awaitStarted();
        inbound.awaitClosed();
        ControlledDeadline backendPhase = deadlines.awaitStarted();
        assertThat(backendCompleted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        ControlledDeadline responseWrite = deadlines.awaitResponseStarted();

        backendPhase.awaitClosed();
        assertThat(writeStarted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(responseWrite.duration()).isEqualTo(responseWriteDeadline);
        assertThat(call).isNotDone();

        responseWrite.expire();
        assertThat(writerInterrupted.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        call.handle((response, failure) -> null).get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        awaitIdle();
        responseWrite.awaitClosed();
        assertIdle();
    }

    @Test
    void callerDisconnectRacingDeprovisionDoesNotRetainPermitOrBytes() throws Exception {
        CountDownLatch backendArrived = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        HttpServer backend = backend(exchange -> {
            backendArrived.countDown();
            await(releaseBackend);
            respond(exchange, 200, "late".getBytes(StandardCharsets.UTF_8));
        });
        proxy = new RoundRobinFunctionProxy(
                "127.0.0.1", 1, Duration.ofSeconds(30), HttpClient.newHttpClient(), limits(64, 64, 128));
        proxy.updateBackends(List.of(baseUrl(backend)));
        Socket caller = connect();
        caller.getOutputStream().write(("POST /invoke HTTP/1.1\r\n" // NOSONAR (java:S6126): raw HTTP needs explicit CRLF line endings
                + "Host: 127.0.0.1\r\n"
                + "Content-Length: 1\r\n\r\nx").getBytes(StandardCharsets.US_ASCII));
        caller.getOutputStream().flush();
        assertThat(backendArrived.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(proxy.snapshot().inFlight()).isEqualTo(1);

        CountDownLatch race = new CountDownLatch(1);
        CompletableFuture<Void> disconnect = CompletableFuture.runAsync(() -> {
            await(race);
            close(caller);
        });
        RoundRobinFunctionProxy closingProxy = proxy;
        CompletableFuture<Void> deprovision = CompletableFuture.runAsync(() -> {
            await(race);
            closingProxy.close();
        });
        race.countDown();
        releaseBackend.countDown();

        disconnect.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        deprovision.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertIdle();
        proxy = null;
    }

    @Test
    void healthRemainsResponsiveWhileInvocationCountAndRequestOwnerBudgetAreSaturated() throws Exception {
        CountDownLatch backendArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer backend = backend(exchange -> {
            backendArrived.countDown();
            await(release);
            respond(exchange, 200, "x".getBytes(StandardCharsets.UTF_8));
        });
        proxy = new RoundRobinFunctionProxy(
                "127.0.0.1", 1, Duration.ofSeconds(5), HttpClient.newHttpClient(), limits(16, 1, 33));
        proxy.updateBackends(List.of(baseUrl(backend)));
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> invocation = postAsync(client, new byte[16]);
        assertThat(backendArrived.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(healthUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).isEqualTo("UP");
        release.countDown();
        assertThat(invocation.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
        assertIdle();
    }

    @Test
    void successfulRoundTripReleasesRequestAndResponseBuffers() throws Exception {
        HttpServer backend = backend(exchange -> respond(exchange, 200, new byte[32]));
        proxy = proxy(limits(64, 64, 160));
        proxy.updateBackends(List.of(baseUrl(backend)));

        assertThat(post(new byte[32]).statusCode()).isEqualTo(200);

        assertIdle();
    }

    @Test
    void invalidBackendUriAfterReadingBodyStillReleasesTheRequestBuffer() throws Exception {
        proxy = proxy(limits(64, 64, 128));
        proxy.updateBackends(List.of("not a valid URI"));

        HttpResponse<String> response = post(new byte[32]);

        assertThat(response.statusCode()).isEqualTo(502);
        assertIdle();
    }

    @Test
    void ordinaryOneKiBPayloadMeasurement() throws Exception {
        byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 'x');
        HttpServer backend = backend(exchange -> respond(exchange, 200, payload));
        proxy = proxy(new ProxySettings(
                1024 * 1024, 4 * 1024 * 1024, 32L * 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        proxy.updateBackends(List.of(baseUrl(backend)));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = request(payload);
        for (int i = 0; i < 20; i++) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
        }

        int measuredCalls = 200;
        long started = System.nanoTime();
        for (int i = 0; i < measuredCalls; i++) {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).hasSize(payload.length);
        }
        long elapsedNanos = System.nanoTime() - started;
        double averageMicros = elapsedNanos / 1_000.0 / measuredCalls;
        double callsPerSecond = measuredCalls * 1_000_000_000.0 / elapsedNanos;
        System.out.printf("P13 ordinary payload: calls=%d bytes=%d avg_us=%.1f throughput_rps=%.1f%n",
                measuredCalls, payload.length, averageMicros, callsPerSecond);
        assertIdle();
    }

    private RoundRobinFunctionProxy proxy(ProxySettings properties) {
        return new RoundRobinFunctionProxy(
                "127.0.0.1", 4, Duration.ofSeconds(5), HttpClient.newHttpClient(), properties);
    }

    private static ProxySettings limits(int request, int response, long aggregate) {
        return new ProxySettings(
                request, response, aggregate, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private HttpServer backend(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executors.add(executor);
        server.setExecutor(executor);
        server.createContext("/invoke", handler);
        server.start();
        backends.add(server);
        return server;
    }

    private HttpResponse<String> post(byte[] body) throws Exception {
        return HttpClient.newHttpClient().send(request(body), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> postAsync(HttpClient client, byte[] body) {
        return client.sendAsync(request(body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(byte[] body) {
        return HttpRequest.newBuilder(URI.create(proxy.endpointUrl()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private Socket connect() throws IOException {
        URI endpoint = URI.create(proxy.endpointUrl());
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()));
        socket.setSoTimeout((int) AWAIT.toMillis());
        return socket;
    }

    private int readStatus(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] line = new byte[128];
        int size = 0;
        while (size < line.length) {
            int next = input.read();
            if (next < 0 || next == '\n') {
                break;
            }
            line[size++] = (byte) next;
        }
        String statusLine = new String(line, 0, size, StandardCharsets.US_ASCII).trim();
        return Integer.parseInt(statusLine.split(" ")[1]);
    }

    private int readStatusOrClosed(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        int first = input.read();
        if (first < 0) {
            return -1;
        }
        byte[] remainder = new byte[127];
        int size = 0;
        while (size < remainder.length) {
            int next = input.read();
            if (next < 0 || next == '\n') {
                break;
            }
            remainder[size++] = (byte) next;
        }
        String statusLine = ((char) first
                + new String(remainder, 0, size, StandardCharsets.US_ASCII)).trim();
        return Integer.parseInt(statusLine.split(" ")[1]);
    }

    private String healthUrl() {
        return proxy.endpointUrl().replace("/invoke", "/health");
    }

    private void awaitIdle() throws Exception {
        CompletableFuture.runAsync(() -> {
            while (proxy.snapshot().inFlight() != 0 || proxy.snapshot().bufferedBytes() != 0) {
                Thread.onSpinWait();
            }
        }).get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void assertIdle() {
        assertThat(proxy.snapshot()).isEqualTo(new RoundRobinFunctionProxy.Snapshot(0, 0));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException _) {
            // Best effort: the peer may already have closed the socket.
        }
    }

    private static final class ControlledDeadlineFactory implements RoundRobinFunctionProxy.DeadlineFactory {
        private final BlockingQueue<ControlledDeadline> started = new LinkedBlockingQueue<>();
        private final CompletableFuture<ControlledDeadline> responseStarted = new CompletableFuture<>();
        private final AtomicInteger phase = new AtomicInteger();
        private volatile ControlledDeadline backendDeadline;

        @Override
        public RoundRobinFunctionProxy.Deadline start(Duration duration, Runnable abort) {
            ControlledDeadline deadline = new ControlledDeadline(duration, abort, Thread.currentThread());
            int currentPhase = phase.getAndIncrement();
            if (currentPhase == 1) {
                backendDeadline = deadline;
            }
            if (currentPhase == 2) {
                if (!backendDeadline.isClosed()) {
                    AssertionError failure = new AssertionError(
                            "response deadline created before backend deadline closed");
                    responseStarted.completeExceptionally(failure);
                    throw failure;
                }
                responseStarted.complete(deadline);
            } else {
                started.add(deadline);
            }
            return deadline;
        }

        private ControlledDeadline awaitStarted() throws InterruptedException {
            ControlledDeadline deadline = started.poll(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(deadline).isNotNull();
            return deadline;
        }

        private ControlledDeadline awaitResponseStarted() throws Exception {
            return responseStarted.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static final class ControlledDeadline implements RoundRobinFunctionProxy.Deadline {
        private final Duration duration;
        private final Runnable abort;
        private final Thread owner;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean expired = new AtomicBoolean();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();

        private ControlledDeadline(Duration duration, Runnable abort, Thread owner) {
            this.duration = duration;
            this.abort = abort;
            this.owner = owner;
        }

        private Duration duration() {
            return duration;
        }

        private void expire() {
            if (completed.compareAndSet(false, true)) {
                expired.set(true);
                abort.run();
                owner.interrupt();
            }
        }

        private void awaitClosed() throws Exception {
            closed.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private boolean isClosed() {
            return closed.isDone();
        }

        @Override
        public boolean expired() {
            return expired.get();
        }

        @Override
        public void close() {
            if (expired.get()) {
                Thread.interrupted();
            }
            completed.set(true);
            closed.complete(null);
        }
    }
}
