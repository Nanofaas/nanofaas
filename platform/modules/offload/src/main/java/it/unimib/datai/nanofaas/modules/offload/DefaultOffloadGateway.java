package it.unimib.datai.nanofaas.modules.offload;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class DefaultOffloadGateway implements OffloadGateway {
    private static final Logger log = LoggerFactory.getLogger(DefaultOffloadGateway.class);
    // ponytail: fixed margin so the gateway's remote budget expires before the
    // coordinator's local wait, making 504 (not a local "timeout") deterministic
    private static final long TIMEOUT_MARGIN_MS = 50;

    private final OffloadProperties properties;
    private final Supplier<WebClient> webClient;
    private final Supplier<MeterRegistry> meterRegistry;

    public DefaultOffloadGateway(OffloadProperties properties, WebClient webClient, MeterRegistry meterRegistry) {
        this(properties, () -> webClient, () -> meterRegistry);
    }

    /** Lazy suppliers: let the bean exist in contexts without WebClient/MeterRegistry (test slices). */
    public DefaultOffloadGateway(OffloadProperties properties,
                                 Supplier<WebClient> webClient,
                                 Supplier<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.webClient = webClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean enabled() {
        return properties.enabled() && properties.hasTarget();
    }

    @Override
    public boolean shouldOffloadEagerly(FunctionSpec spec) {
        OffloadPolicy policy = spec.offload();
        return policy != null
                && !Boolean.FALSE.equals(policy.enabled())
                && OffloadPolicy.MODE_ALWAYS.equalsIgnoreCase(policy.mode());
    }

    @Override
    public boolean shouldOffloadOnPressure(FunctionSpec spec, SyncQueueRejectReason reason) {
        OffloadPolicy policy = spec.offload();
        return properties.pressureEnabled()
                && (policy == null || !Boolean.FALSE.equals(policy.enabled()));
    }

    @Override
    public String targetUrl(FunctionSpec spec) {
        OffloadPolicy policy = spec.offload();
        if (policy != null && policy.targetUrl() != null && !policy.targetUrl().isBlank()) {
            return policy.targetUrl();
        }
        return properties.targetUrl();
    }

    @Override
    public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context) {
        String target = targetUrl(task.functionSpec());
        String uri = target + "/v1/functions/" + task.functionName() + ":invoke";
        long timeoutMs = Math.max(1, task.functionSpec().timeoutMs() - TIMEOUT_MARGIN_MS);

        WebClient.RequestBodySpec request = webClient.get().post().uri(uri);
        request.header("X-NanoFaaS-Offload-Hop", "1");
        if (task.traceId() != null) {
            request.header("X-Trace-Id", task.traceId());
        }
        if (context.traceparent() != null) {
            request.header("traceparent", context.traceparent());
        }
        if (context.tracestate() != null) {
            request.header("tracestate", context.tracestate());
        }

        return request.bodyValue(task.request())
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(InvocationResponse.class).map(this::toResult);
                    }
                    if (response.statusCode().value() == 404) {
                        return Mono.just(InvocationResult.error(OFFLOAD_FAILED_CODE,
                                "function '" + task.functionName() + "' not registered on remote " + target));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> InvocationResult.error(OFFLOAD_FAILED_CODE,
                                    "remote " + target + " returned " + response.statusCode().value()
                                            + (body.isBlank() ? "" : ": " + body)));
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(TimeoutException.class, ex ->
                        Mono.just(InvocationResult.error(OFFLOAD_TIMEOUT_CODE,
                                "remote " + target + " did not answer within " + timeoutMs + "ms")))
                .onErrorResume(ex -> {
                    log.warn("Offload call to {} failed for function {}", target, task.functionName(), ex);
                    String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    return Mono.just(InvocationResult.error(OFFLOAD_FAILED_CODE,
                            "remote " + target + " unreachable: " + message));
                })
                .doOnNext(result -> recordMetrics(task.functionName(), trigger, result));
    }

    private InvocationResult toResult(InvocationResponse response) {
        return switch (response.status() == null ? "" : response.status()) {
            case "success" -> InvocationResult.success(response.output());
            case "timeout" -> InvocationResult.error("REMOTE_TIMEOUT", "remote execution timed out");
            default -> response.error() != null
                    ? new InvocationResult(false, null, response.error())
                    : InvocationResult.error("REMOTE_ERROR", "remote execution failed with status " + response.status());
        };
    }

    private void recordMetrics(String functionName, OffloadTrigger trigger, InvocationResult result) {
        MeterRegistry registry = meterRegistry.get();
        registry.counter("nanofaas.offload",
                "function", functionName,
                "trigger", trigger.name().toLowerCase()).increment();
        if (result.error() != null
                && (OFFLOAD_FAILED_CODE.equals(result.error().code()) || OFFLOAD_TIMEOUT_CODE.equals(result.error().code()))) {
            registry.counter("nanofaas.offload.failure", "function", functionName).increment();
        }
    }
}
