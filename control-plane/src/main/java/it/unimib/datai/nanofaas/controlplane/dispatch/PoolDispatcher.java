package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@Component
public class PoolDispatcher implements Dispatcher {
    private final WebClient webClient;

    public PoolDispatcher(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
        String endpoint = task.functionSpec().endpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return CompletableFuture.completedFuture(
                    DispatchResult.warm(InvocationResult.error("POOL_ENDPOINT_MISSING", "endpointUrl is required for POOL mode")));
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

                    if (response.statusCode().is2xxSuccessful()) {
                        MediaType contentType = response.headers().contentType()
                                .orElse(MediaType.APPLICATION_JSON);
                        if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
                            return response.bodyToMono(String.class)
                                    .map(body -> new DispatchResult(InvocationResult.success(body), isCold, initMs));
                        }
                        return response.bodyToMono(Object.class)
                                .map(body -> new DispatchResult(InvocationResult.success(body), isCold, initMs));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty(response.statusCode().toString())
                            .map(msg -> new DispatchResult(InvocationResult.error("POOL_ERROR", msg), isCold, initMs));
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(TimeoutException.class, ex -> reactor.core.publisher.Mono.just(
                        DispatchResult.warm(InvocationResult.error("POOL_TIMEOUT", "Pool request timed out after " + timeoutMs + "ms"))))
                .onErrorResume(ex -> reactor.core.publisher.Mono.just(
                        DispatchResult.warm(InvocationResult.error("POOL_ERROR", ex.getMessage()))))
                .toFuture();
    }

    private static Long parseInitDuration(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
