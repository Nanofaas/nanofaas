package it.unimib.datai.nanofaas.modules.offload;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
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
    private static final String REMOTE_PREFIX = "remote ";

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
        // A function-level targetUrl also activates offload: the target check is
        // per-spec in the decision methods, not global here.
        return properties.enabled();
    }

    @Override
    public boolean shouldOffloadEagerly(FunctionSpec spec) {
        OffloadPolicy policy = spec.offload();
        return policy != null
                && !Boolean.FALSE.equals(policy.enabled())
                && OffloadPolicy.MODE_ALWAYS.equalsIgnoreCase(policy.mode())
                && targetUrl(spec) != null;
    }

    @Override
    public boolean shouldOffloadOnPressure(FunctionSpec spec) {
        OffloadPolicy policy = spec.offload();
        return properties.pressureEnabled()
                && (policy == null || !Boolean.FALSE.equals(policy.enabled()))
                && targetUrl(spec) != null;
    }

    @Override
    public String targetUrl(FunctionSpec spec) {
        OffloadPolicy policy = spec.offload();
        String url = (policy != null && policy.targetUrl() != null && !policy.targetUrl().isBlank())
                ? policy.targetUrl()
                : properties.targetUrl();
        if (url == null) {
            return null;
        }
        url = url.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isBlank() ? null : url;
    }

    @Override
    public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context, int timeoutBudgetMs) {
        String target = targetUrl(task.functionSpec());
        String uri = target + "/v1/functions/" + task.functionName() + ":invoke";
        long timeoutMs = Math.max(1, timeoutBudgetMs - TIMEOUT_MARGIN_MS);

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
                        return response.bodyToMono(InvocationResponse.class)
                                .map(this::toResult)
                                .switchIfEmpty(Mono.error(new OffloadFailedException(target, false,
                                        "empty response body from remote " + target)));
                    }
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().then(Mono.error(new OffloadFailedException(target, false,
                                "function '" + task.functionName() + "' not registered on remote " + target)));
                    }
                    int status = response.statusCode().value();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new OffloadFailedException(target, false,
                                    REMOTE_PREFIX + target + " returned " + status
                                            + (body.isBlank() ? "" : ": " + body))));
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorMap(TimeoutException.class, ex ->
                        new OffloadFailedException(target, true,
                                REMOTE_PREFIX + target + " did not answer within " + timeoutMs + "ms"))
                .onErrorMap(ex -> !(ex instanceof OffloadFailedException), ex -> {
                    log.warn("Offload call to {} failed for function {}", target, task.functionName(), ex);
                    String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                    return new OffloadFailedException(target, false,
                            REMOTE_PREFIX + target + " unreachable: " + message);
                })
                .doOnSubscribe(s -> meterRegistry.get().counter("nanofaas.offload",
                        "function", task.functionName(),
                        "trigger", trigger.name().toLowerCase()).increment())
                .doOnError(OffloadFailedException.class, ex ->
                        meterRegistry.get().counter("nanofaas.offload.failure",
                                "function", task.functionName()).increment());
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

}
