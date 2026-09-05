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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class DefaultOffloadGateway implements OffloadGateway {
    private static final Logger log = LoggerFactory.getLogger(DefaultOffloadGateway.class);
    // ponytail: fixed margin so the gateway's remote budget expires before the
    // coordinator's local wait, making 504 (not a local "timeout") deterministic
    private static final long TIMEOUT_MARGIN_MS = 50;
    private static final String REMOTE_PREFIX = "remote ";

    /**
     * Application headers that must never cross the offload hop as real HTTP headers.
     *
     * <p>The remote control plane rebuilds {@code InvocationRequest.headers()} from the HTTP
     * transport of the offload hop ({@code InvocationController.withCallerHeaders}), so the
     * gateway forwards the caller's application headers as real HTTP headers on the second
     * hop. This set keeps that copy away from (a) transport framing the HTTP client owns —
     * {@code host}, {@code content-length}, and above all {@code content-type}, which must
     * keep describing the JSON envelope rather than the caller's original body; (b) headers
     * this gateway sets itself and that dedicated handling must keep control of — the
     * offload-hop marker and the tracing headers; and (c) the reserved headers the receiving
     * control plane binds to dedicated parameters. The list intentionally mirrors
     * {@code InvocationController.EXCLUDED_REQUEST_HEADERS} (lower-cased): forwarding a
     * name the receiver would drop anyway is pointless, and an application value must never
     * be able to masquerade as a control-plane header.
     *
     * <p>Headers nominated hop-by-hop by the caller's {@code Connection} field are not listed
     * here because they never reach this map: {@code InvocationController.withCallerHeaders}
     * already strips them on the first hop.
     */
    private static final Set<String> EXCLUDED_FORWARD_HEADERS = Set.of(
            "content-length", "content-type", "host", "transfer-encoding",
            "accept", "user-agent",
            "x-execution-id", "x-trace-id", "x-dispatch-attempt", "x-timeout-ms",
            "x-nanofaas-offload-hop", "idempotency-key", "traceparent", "tracestate");

    /**
     * RFC 9110 hop-by-hop headers plus the de-facto {@code Proxy-Connection} extension.
     * They name per-connection semantics and would be meaningless — or actively harmful — on
     * the next hop, whose connection is a different one. The receiving controller leaves most
     * of them in the envelope (it only strips {@code connection}, {@code keep-alive} and
     * {@code transfer-encoding}), so the gateway must not copy them. {@code proxy-authenticate}
     * and {@code proxy-authorization} are excluded through the {@code proxy-} prefix below.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-connection", "te", "trailer", "upgrade");

    private static final String PROXY_HEADER_PREFIX = "proxy-";

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

        forwardApplicationHeaders(request, task.request().headers());

        return request.bodyValue(task.request())
                .exchangeToMono(response -> {
                    boolean functionDecided = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-NanoFaaS-Function-Status"));

                    // A marked response is the function's own answer, whatever its status —
                    // read it down the same body-parsing path as a plain 2xx, before any
                    // status-based branching (a marker-bearing 404 is a function decision,
                    // not "unregistered function").
                    if (functionDecided || response.statusCode().is2xxSuccessful()) {
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

    /**
     * Copy the caller's application headers onto the offload hop as real HTTP headers.
     *
     * <p>Best-effort and defensive: reserved names, hop-by-hop names and any {@code proxy-*}
     * header are skipped, and the gateway's own hop/tracing headers set above are never
     * overwritten because they are excluded here. A {@code null} value is skipped rather than
     * sent (WebClient would reject it).
     */
    private static void forwardApplicationHeaders(WebClient.RequestBodySpec request,
                                                  Map<String, String> applicationHeaders) {
        if (applicationHeaders == null || applicationHeaders.isEmpty()) {
            return;
        }
        applicationHeaders.forEach((name, value) -> {
            if (name == null || value == null) {
                return;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (EXCLUDED_FORWARD_HEADERS.contains(key)
                    || HOP_BY_HOP_HEADERS.contains(key)
                    || key.startsWith(PROXY_HEADER_PREFIX)) {
                return;
            }
            request.header(name, value);
        });
    }

    private InvocationResult toResult(InvocationResponse response) {
        return switch (response.status() == null ? "" : response.status()) {
            case "success" -> InvocationResult.successWithEnvelope(
                    response.output(), response.statusCode(), response.headers(), response.encoding());
            case "timeout" -> InvocationResult.error("REMOTE_TIMEOUT", "remote execution timed out");
            default -> response.error() != null
                    ? new InvocationResult(false, null, response.error())
                    : InvocationResult.error("REMOTE_ERROR", "remote execution failed with status " + response.status());
        };
    }

}
