package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@Component
public class ExternalDispatcher implements Dispatcher {
    // ponytail: plain JsonMapper (not the app's configured Spring ObjectMapper bean) — this
    // is a narrow re-decode of a response body whose Content-Type label lied about its actual
    // JSON encoding; not worth widening the constructor for. Revisit if a custom Jackson
    // module is ever required for envelope bodies.
    private static final JsonMapper JSON_FALLBACK_MAPPER = JsonMapper.shared();

    private final WebClient webClient;

    public ExternalDispatcher(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
        String endpoint = task.functionSpec().endpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return CompletableFuture.completedFuture(
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
            reactor.netty.http.client.HttpClientRequest reactorRequest = clientHttpRequest.getNativeRequest();
            reactorRequest.responseTimeout(Duration.ofMillis(timeoutMs));
        });

        return request.bodyValue(task.request())
                .exchangeToMono(response -> {
                    boolean isCold = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-Cold-Start"));
                    Long initMs = parseInitDuration(
                            response.headers().asHttpHeaders().getFirst("X-Init-Duration-Ms"));
                    boolean isFunctionDecided = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-NanoFaaS-Function-Status"));

                    if (isFunctionDecided) {
                        int statusCode = response.statusCode().value();
                        if (ResponseHeaderPolicy.isStatusCodeValid(statusCode)) {
                            Map<String, String> headers = extractAllowedHeaders(response.headers().asHttpHeaders());
                            String encoding = response.headers().asHttpHeaders().getFirst("X-NanoFaaS-Encoding");
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
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty(response.statusCode().toString())
                            .map(msg -> new DispatchResult(InvocationResult.error("EXTERNAL_ERROR", msg), isCold, initMs));
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(TimeoutException.class, ex -> reactor.core.publisher.Mono.just(
                        DispatchResult.warm(InvocationResult.error("EXTERNAL_TIMEOUT", "External request timed out after " + timeoutMs + "ms"))))
                .onErrorResume(ex -> reactor.core.publisher.Mono.just(
                        DispatchResult.warm(InvocationResult.error("EXTERNAL_ERROR", ex.getMessage()))))
                .toFuture();
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
        MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
            return response.bodyToMono(String.class).cast(Object.class);
        }
        if (lenientJsonFallback) {
            return response.bodyToMono(String.class)
                    .flatMap(raw -> {
                        if (raw.isBlank()) {
                            return Mono.empty();
                        }
                        try {
                            return Mono.justOrEmpty(JSON_FALLBACK_MAPPER.readValue(raw, Object.class));
                        } catch (JacksonException ex) {
                            // Malformed body on the marker path is a genuine transport/serialization
                            // failure, not an empty response — must not be silently reported as
                            // success=true with output=null. Propagate so the outer onErrorResume
                            // turns it into an EXTERNAL_ERROR (success=false, retryable).
                            return Mono.error(ex);
                        }
                    });
        }
        return response.bodyToMono(Object.class);
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
