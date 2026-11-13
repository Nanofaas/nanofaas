package it.unimib.datai.nanofaas.containerdeployment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

/** Test transport for the socket-free pool; each request goes directly to its reserved SDK. */
final class DirectReplicaFixture implements AutoCloseable {
    private final HttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ManagedReplicaPool pool;
    private final AtomicInteger active = new AtomicInteger();
    private Duration timeout;

    DirectReplicaFixture(HttpClient client, int limit, Duration timeout) {
        this.client = client; this.timeout = timeout;
        pool = new ManagedReplicaPool(new HttpRuntimeExecutionProbe(client, Duration.ofSeconds(2)), executor, Duration.ofMinutes(10));
        pool.updateLimits(limit, timeout);
    }
    String endpointUrl() { return "http://unresolved.invalid/invoke"; }
    void enablePhysicalSlots() { pool.enablePhysicalSlots(); }
    void updateBackends(List<String> backends) { pool.updateBackends(backends); }
    void updateLimits(int limit, Duration duration) { timeout = duration; pool.updateLimits(limit, duration); }
    void beginDrain(String backend) { pool.beginDrain(backend); }
    boolean awaitDrained(String backend, Duration duration) { return pool.awaitDrained(backend, duration); }
    ReplicaSlots.QuiesceToken beginQuiesce() { return pool.beginQuiesce(); }
    boolean awaitQuiesced(Duration duration) { return pool.awaitQuiesced(duration); }
    boolean resume(ReplicaSlots.QuiesceToken token, String epoch) { return pool.resume(token, epoch); }
    boolean resumeRetained(ReplicaSlots.QuiesceToken token, String epoch) { return pool.resumeRetained(token, epoch); }
    Snapshot snapshot() { return new Snapshot(active.get()); }
    record Snapshot(int inFlight) { }
    CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try { return send(request); } catch (Exception failure) { throw new CompletionException(failure); }
        }, executor);
    }
    HttpResponse<String> send(HttpRequest request) throws Exception {
        active.incrementAndGet();
        try (var target = pool.acquire(request.headers().firstValue("X-Execution-Id").orElseThrow(),
                request.headers().firstValue("X-Dispatch-Attempt").orElse("1"))) {
            if (target == null) return new Response(503, "busy", HttpHeaders.of(Map.of(), (a,b) -> true), request);
            try { target.ready().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (TimeoutException | ExecutionException failure) {
                // As ExternalDispatcher: a wait for a replica that ended without one is "busy".
                boolean refused = failure.getCause() instanceof RejectedExecutionException;
                return new Response(refused ? 503 : 504, refused ? "busy" : "timeout", HttpHeaders.of(Map.of(), (a,b) -> true), request);
            }
            if (!target.markDispatched()) return new Response(503, "unavailable", HttpHeaders.of(Map.of(), (a,b) -> true), request);
            var direct = HttpRequest.newBuilder(request, (a,b) -> true).uri(URI.create(target.endpointUrl())).timeout(timeout).build();
            var response = client.send(direct, HttpResponse.BodyHandlers.ofString());
            var headers = new TreeMap<String,List<String>>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(response.headers().map()); headers.remove("X-NanoFaaS-Handler-Executed");
            if (response.statusCode() >= 400 && target.executionEvidence().toCompletableFuture().get(3, TimeUnit.SECONDS)) {
                headers.put("X-NanoFaaS-Handler-Executed", List.of("true"));
            }
            return new Response(response.statusCode(), response.body(), HttpHeaders.of(headers, (a,b) -> true), direct);
        } finally { active.decrementAndGet(); }
    }
    @Override public void close() { pool.close(); executor.shutdownNow(); executor.close(); }
    private record Response(int statusCode, String body, HttpHeaders headers, HttpRequest request) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
