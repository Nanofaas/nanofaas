package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.CompletableFuture;

@Component
public class PoolDispatcher implements Dispatcher {
    private final WebClient webClient;

    public PoolDispatcher(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
        String endpoint = task.functionSpec().endpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return CompletableFuture.completedFuture(
                    InvocationResult.error("POOL_ENDPOINT_MISSING", "endpointUrl is required for POOL mode"));
        }

        WebClient.RequestBodySpec request = webClient.post()
                .uri(endpoint)
                .header("X-Execution-Id", task.executionId());

        if (task.traceId() != null) {
            request.header("X-Trace-Id", task.traceId());
        }
        if (task.idempotencyKey() != null) {
            request.header("Idempotency-Key", task.idempotencyKey());
        }

        return request.bodyValue(task.request())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        MediaType contentType = response.headers().contentType()
                                .orElse(MediaType.APPLICATION_JSON);
                        if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
                            return response.bodyToMono(String.class).map(InvocationResult::success);
                        }
                        return response.bodyToMono(Object.class).map(InvocationResult::success);
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty(response.statusCode().toString())
                            .map(msg -> InvocationResult.error("POOL_ERROR", msg));
                })
                .onErrorResume(ex -> reactor.core.publisher.Mono.just(
                        InvocationResult.error("POOL_ERROR", ex.getMessage())))
                .toFuture();
    }
}
