package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@Component
public class ExternalDispatcher implements Dispatcher {
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
                            return decodeBody(response)
                                    // ponytail: a function-decided response with a content type our decoder
                                    // doesn't know (e.g. application/pdf) still carries a legitimate
                                    // status/headers envelope; don't let a body-decode gap collapse it into
                                    // EXTERNAL_ERROR. Body decoding beyond JSON/text/plain is out of Task 8
                                    // scope (see base64 `encoding` field, later tasks).
                                    .onErrorResume(UnsupportedMediaTypeException.class, ex -> Mono.empty())
                                    .map(body -> new DispatchResult(
                                            InvocationResult.successWithEnvelope(body, statusCode, headers, null),
                                            isCold, initMs))
                                    .defaultIfEmpty(new DispatchResult(
                                            InvocationResult.successWithEnvelope(null, statusCode, headers, null),
                                            isCold, initMs));
                        }
                        // ponytail: out-of-range status code from a marker-bearing response is not
                        // spec-legal (brief is silent) — fall through and treat as a platform error.
                    }

                    if (response.statusCode().is2xxSuccessful()) {
                        return decodeBody(response)
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

    private static Mono<Object> decodeBody(ClientResponse response) {
        MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
            return response.bodyToMono(String.class).cast(Object.class);
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
