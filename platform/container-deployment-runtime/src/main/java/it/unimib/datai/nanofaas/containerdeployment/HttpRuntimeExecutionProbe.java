package it.unimib.datai.nanofaas.containerdeployment;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
public final class HttpRuntimeExecutionProbe implements RuntimeExecutionProbe {
    private final HttpClient client;
    private final Duration timeout;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public HttpRuntimeExecutionProbe(HttpClient client, Duration timeout) { this.client=client; this.timeout=timeout; }
    public ExecutionObservation observe(URI backend, String id) {
        var json=read(backend.resolve("/runtime/executions/"+URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20")));
        if(json==null || ("RELEASED".equals(json.path("state").asText()) &&
            (!json.path("occupancySeconds").isNumber() || !Double.isFinite(json.path("occupancySeconds").asDouble()) || json.path("occupancySeconds").asDouble()<0))) return ExecutionObservation.unknown();
        return new ExecutionObservation(json.path("state").asText(),json.path("incarnation").asText(),json.path("executionId").asText(), json.path("dispatchAttempt").isNull()?null:json.path("dispatchAttempt").asText());
    }
    public String eligibleIncarnation(URI backend) {
        var json=read(backend.resolve("/runtime/status"));
        if(json==null || json.path("schemaVersion").asInt()!=1 || !json.path("physicalReleaseProof").asBoolean()
            || json.path("maxConcurrentHandlers").asInt()!=1 || !json.path("activeHandlers").isNumber() || json.path("activeHandlers").asInt()!=0
            || json.path("incarnation").asText().isBlank()) throw new IllegalStateException("Runtime lacks idle physical-release capability: "+backend);
        return json.path("incarnation").asText();
    }
    private JsonNode read(URI uri) {
        var request=HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        var pending=client.sendAsync(request, info -> new BoundedJsonSubscriber(64*1024));
        try {
            var response=pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200) return null;
            return mapper.readTree(response.body());
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return null;
        } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) { return null;
        } finally { if(!pending.isDone()) pending.cancel(true); }
    }
    private static final class BoundedJsonSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum; private int size; private java.util.concurrent.Flow.Subscription subscription;
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        BoundedJsonSubscriber(int maximum) { this.maximum=maximum; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription=s; s.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(buffer.remaining()>maximum-size) { subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("Runtime observation too large")); return; }
                byte[] data=new byte[buffer.remaining()]; buffer.get(data); bytes.writeBytes(data); size+=data.length;
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
