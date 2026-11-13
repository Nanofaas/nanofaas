package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy;
import it.unimib.datai.nanofaas.controlplane.json.RawJsonReader;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@Component
public class ExternalDispatcher implements Dispatcher {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExternalDispatcher.class);
    // ponytail: plain JsonMapper (not the app's configured Spring ObjectMapper bean) — this
    // is a narrow re-decode of a response body whose Content-Type label lied about its actual
    // JSON encoding; not worth widening the constructor for. Revisit if a custom Jackson
    // module is ever required for envelope bodies.
    private static final JsonMapper JSON_FALLBACK_MAPPER = JsonMapper.shared();

    private final WebClient webClient;
    private final Clock clock;

    @Autowired
    public ExternalDispatcher(WebClient webClient) {
        this(webClient, Clock.systemUTC());
    }

    ExternalDispatcher(WebClient webClient, Clock clock) {
        this.webClient = webClient;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
        return dispatch(task, null);
    }

    public CompletableFuture<DispatchResult> dispatch(InvocationTask task, ManagedInvocationTarget target) {
        InvocationLogs.event(LOG, task, "dispatch-external-started");
        long timeoutMs = task.functionSpec().timeoutMs();
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> { if (target != null && released.compareAndSet(false, true)) target.close(); };
        return Mono.defer(() -> target == null ? send(task, null)
                        : Mono.fromCompletionStage(target.ready()).then(Mono.defer(() -> send(task, target))))
                .flatMap(result -> target == null || result.result().success() ? Mono.just(result)
                        : Mono.fromCompletionStage(target.executionEvidence())
                            .onErrorReturn(false)
                            .map(executed -> new DispatchResult(result.result(), result.coldStart(),
                                    result.initDurationMs(), result.retryNotBefore(), executed)))
                // Release the replica as soon as it has answered (and any execution evidence is
                // in), before the result runs the completion chain behind the returned future.
                // That chain gives the function's capacity back first; released only in
                // doFinally, the replica still looked reserved to the invocation admitted next,
                // which was refused with a 503. doFinally still covers every path that ends
                // without an answer.
                .doOnNext(ignored -> release.run())
                .timeout(Duration.ofMillis(timeoutMs))
                // A reservation that waited for a replica and got none is the same refusal as a
                // reservation the pool could not make at once (DispatcherRouter): no replica.
                .onErrorResume(java.util.concurrent.RejectedExecutionException.class, ex -> Mono.just(
                        DispatchResult.warm(platformFailure(503, "RUNTIME_HANDLER_SATURATED", ex.getMessage(), null))))
                .onErrorResume(ExternalDispatcher::isTimeout, ex -> Mono.just(
                        DispatchResult.warm(platformFailure(504, "EXTERNAL_TIMEOUT", "External request timed out after " + timeoutMs + "ms", null))))
                .onErrorResume(ex -> Mono.just(
                        DispatchResult.warm(platformFailure(502, "EXTERNAL_ERROR", ex.getMessage(), null))))
                .doOnNext(ignored -> InvocationLogs.event(LOG, task, "dispatch-external-completed"))
                .doFinally(ignored -> release.run())
                .toFuture();
    }

    private Mono<DispatchResult> send(InvocationTask task, ManagedInvocationTarget target) {
        String endpoint = target == null ? task.functionSpec().endpointUrl() : target.endpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return Mono.just(
                    DispatchResult.warm(InvocationResult.error("EXTERNAL_ENDPOINT_MISSING", "endpointUrl is required for EXTERNAL mode")));
        }

        long timeoutMs = task.functionSpec().timeoutMs();

        WebClient.RequestBodySpec request = webClient.post()
                .uri(endpoint)
                .header("X-Execution-Id", task.executionId())
                .header("X-Dispatch-Attempt", String.valueOf(task.attempt()));

        if (task.traceId() != null) {
            request.header("X-Trace-Id", task.traceId());
        }
        if (task.idempotencyKey() != null) {
            request.header("Idempotency-Key", task.idempotencyKey());
        }

        request.httpRequest(clientHttpRequest -> {
            if (target != null && !target.markDispatched()) throw new IllegalStateException("Managed target no longer admissible");
            reactor.netty.http.client.HttpClientRequest reactorRequest = clientHttpRequest.getNativeRequest();
            reactorRequest.responseTimeout(Duration.ofMillis(timeoutMs));
        });

        return request.bodyValue(task.request())
                .exchangeToMono(response -> {
                    // One adapter for the whole response: asHttpHeaders() was called six
                    // times over the same headers, on every dispatch.
                    org.springframework.http.HttpHeaders responseHeaders = response.headers().asHttpHeaders();
                    boolean handlerExecuted = false;
                    boolean isCold = "true".equalsIgnoreCase(responseHeaders.getFirst("X-Cold-Start"));
                    Long initMs = parseInitDuration(responseHeaders.getFirst("X-Init-Duration-Ms"));
                    boolean isFunctionDecided = "true".equalsIgnoreCase(
                            responseHeaders.getFirst("X-NanoFaaS-Function-Status"));

                    if (isFunctionDecided) {
                        int statusCode = response.statusCode().value();
                        if (ResponseHeaderPolicy.isStatusCodeValid(statusCode)) {
                            Map<String, String> headers = extractAllowedHeaders(responseHeaders);
                            String encoding = responseHeaders.getFirst("X-NanoFaaS-Encoding");
                            return decodeBody(response, true)
                                    .map(body -> new DispatchResult(
                                            InvocationResult.successWithEnvelope(body, statusCode, headers, encoding),
                                            isCold, initMs))
                                    .defaultIfEmpty(new DispatchResult(
                                            InvocationResult.successWithEnvelope(null, statusCode, headers, encoding),
                                            isCold, initMs));
                        }
                        // An out-of-range status on a marker-bearing response is not spec-legal
                        // ([200,599] only). Fall through to the platform-error path rather than
                        // trusting it: EXTERNAL/DEPLOYMENT endpoints are arbitrary unauthenticated
                        // URLs, and HTTP's status-line grammar is 3DIGIT, so a misbehaving upstream
                        // can emit one. Covered by
                        // dispatch_functionStatusMarkerWithOutOfRangeStatus_isPlatformErrorNotPassthrough.
                    }

                    if (response.statusCode().is2xxSuccessful()) {
                        return decodeBody(response, false)
                                .map(body -> new DispatchResult(InvocationResult.success(body), isCold, initMs))
                                .defaultIfEmpty(new DispatchResult(InvocationResult.success(null), isCold, initMs));
                    }
                    int status = response.statusCode().value();
                    Instant retryAt = status == 429 || status == 503
                            ? parseRetryAfter(responseHeaders.get("Retry-After"), clock.instant()) : null;
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty(response.statusCode().toString())
                            .map(msg -> new DispatchResult(externalFailure(status, msg, responseHeaders, retryAt),
                                    isCold, initMs, retryAt, handlerExecuted))
                            .onErrorResume(ex -> isTimeout(ex) ? Mono.error(ex) : Mono.just(new DispatchResult(
                                    externalFailure(status, ex.getMessage(), responseHeaders, retryAt),
                                    isCold, initMs, retryAt, handlerExecuted)));
                });
    }

    private static InvocationResult platformFailure(int status, String code, String message, Map<String, String> headers) {
        return new InvocationResult(false, null,
                new it.unimib.datai.nanofaas.common.model.ErrorInfo(code, message), status, headers, null);
    }

    private static InvocationResult externalFailure(int upstreamStatus, String body, HttpHeaders headers, Instant retryAt) {
        int status = switch (upstreamStatus) {
            case 429, 503, 504 -> upstreamStatus;
            default -> 502;
        };
        String code = "EXTERNAL_ERROR";
        String message = body;
        try {
            var error = JSON_FALLBACK_MAPPER.readTree(body == null || body.isBlank() ? "{}" : body).path("error");
            String runtimeCode = error.path("code").asText();
            boolean recognized = upstreamStatus == 429 && ("RUNTIME_HANDLER_SATURATED".equals(runtimeCode)
                    || "RUNTIME_CALLBACK_SATURATED".equals(runtimeCode))
                    || upstreamStatus == 503 && "RUNTIME_STOPPING".equals(runtimeCode);
            if (recognized && error.path("message").isString()) {
                code = runtimeCode;
                message = error.path("message").asText();
            }
        } catch (JacksonException | IllegalArgumentException ignored) {
            // Non-JSON upstream errors retain their original diagnostic body.
        }
        String retryAfter = headers.getFirst("Retry-After");
        Map<String, String> retryHeaders = retryAt == null || retryAfter == null ? null
                : Map.of("Retry-After", retryAfter.trim());
        return platformFailure(status, code, message, retryHeaders);
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof ReadTimeoutException) {
                return true;
            }
        }
        return false;
    }

    static Instant parseRetryAfter(List<String> values, Instant receivedAt) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst().trim();
        if (value.isEmpty()) {
            return null;
        }
        for (String candidate : values) {
            if (!value.equals(candidate.trim())) {
                return null;
            }
        }
        if (value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return receivedAt.plusSeconds(Long.parseLong(value));
            } catch (NumberFormatException | java.time.DateTimeException | ArithmeticException overflow) {
                return Instant.MAX;
            }
        }
        HttpHeaders date = new HttpHeaders();
        date.set("Retry-After", value);
        try {
            long epochMillis = date.getFirstDate("Retry-After");
            return epochMillis < 0 ? null : Instant.ofEpochMilli(epochMillis);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            return null;
        }
    }

    private static Long parseInitDuration(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Decodes a response body, treating {@code text/plain} as a raw string (Spring has no
     * {@code Object.class} decoder for it — see the class-level history of this method).
     *
     * <p>{@code lenientJsonFallback} controls what happens for any other content type:
     * <ul>
     *   <li>{@code false} (today's non-marker path, unchanged): delegate to Spring's
     *       Content-Type-gated {@code bodyToMono(Object.class)}. An unrecognized content type
     *       (e.g. {@code application/pdf}) throws {@code UnsupportedMediaTypeException}, which
     *       the caller lets bubble up to the outer {@code onErrorResume} → {@code EXTERNAL_ERROR}
     *       — exactly today's behavior.</li>
     *   <li>{@code true} (function-decided/marker path): a handler's {@code Content-Type} is a
     *       caller-chosen label, decoupled from the actual wire encoding — InvokeController
     *       always serializes the body as JSON regardless of it. So instead of gating on that
     *       label, read the body once as a string and parse it as JSON ourselves. This also
     *       sidesteps a hard constraint: the Reactor Netty inbound receiver only allows a single
     *       body subscription, so a "try Object.class, then retry on failure" strategy is not
     *       viable here — the retry throws "Rejecting additional inbound receiver".</li>
     * </ul>
     */
    private static Mono<Object> decodeBody(ClientResponse response, boolean lenientJsonFallback) {
        java.util.Optional<MediaType> declared = response.headers().contentType();
        if (lenientJsonFallback || declared.filter(ExternalDispatcher::isJson).isPresent()) {
            // Read the body once, as bytes: the Reactor Netty receiver allows one subscription,
            // and a malformed body must surface as an error (the outer onErrorResume turns it
            // into EXTERNAL_ERROR) - never as success=true with output=null.
            return response.bodyToMono(byte[].class)
                    .flatMap(body -> Mono.justOrEmpty(decodeJson(body, lenientJsonFallback)));
        }
        if (MediaType.TEXT_PLAIN.isCompatibleWith(declared.orElse(MediaType.APPLICATION_JSON))) {
            return response.bodyToMono(String.class).cast(Object.class);
        }
        return response.bodyToMono(Object.class);
    }

    private static boolean isJson(MediaType type) {
        return MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.getSubtype().endsWith("+json");
    }

    /**
     * An object or array is kept as the bytes the function sent: the control plane only forwards
     * a result, so building objects for it and serializing them again was pure overhead, and what
     * it retains is then the text rather than a tree several times its size. A scalar is small
     * whatever it is, and stays the plain value it always was.
     */
    private static Object decodeJson(byte[] body, boolean blankIsEmpty) {
        int first = 0;
        while (first < body.length && isJsonWhitespace(body[first])) {
            first++;
        }
        if (first == body.length && blankIsEmpty) {
            return null;
        }
        if (first < body.length && (body[first] == '{' || body[first] == '[')) {
            return RawJsonReader.read(body);
        }
        return JSON_FALLBACK_MAPPER.readValue(body, Object.class);
    }

    private static boolean isJsonWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static Map<String, String> extractAllowedHeaders(HttpHeaders headers) {
        Map<String, String> raw = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                raw.put(name, values.get(0));
            }
        });
        return ResponseHeaderPolicy.filterAllowedHeaders(raw);
    }
}
