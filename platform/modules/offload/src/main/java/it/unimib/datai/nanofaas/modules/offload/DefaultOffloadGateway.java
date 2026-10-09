package it.unimib.datai.nanofaas.modules.offload;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
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
import it.unimib.datai.nanofaas.controlplane.offload.OffloadMeters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
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
     * keep describing the JSON envelope rather than the caller's original body — framing,
     * content negotiation AND content coding all belong to this hop's own message; (b) headers
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
            // Same class, and previously missed: content-encoding would label a plain JSON
            // envelope as compressed, accept-encoding would invite a response body this
            // client is not configured to decode, and expect belongs to the caller's own
            // exchange. Inert only while server compression happens to be off everywhere.
            "content-encoding", "accept-encoding", "expect",
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
    private static final java.util.regex.Pattern TERMINAL_EXECUTION_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,255}");

    private it.unimib.datai.nanofaas.modules.offload.oneshot.routing.PlanRouter planRouter;
    public void plannedRouting(it.unimib.datai.nanofaas.modules.offload.oneshot.routing.PlanRouter router) { this.planRouter=router; }
    @Override public it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute planRoute(InvocationTask task,OffloadContext context) {
        return planRouter==null?OffloadGateway.super.planRoute(task,context):planRouter.route(task,context);
    }
    @Override public boolean hasPlannedRouting(FunctionSpec spec) { return planRouter!=null && planRouter.manages(spec.name()); }
    private final OffloadProperties properties;
    private final Supplier<WebClient> webClient;
    private final OffloadMeters metrics;
    private final LegacyMeterLifecycle legacyMeters;

    public DefaultOffloadGateway(OffloadProperties properties, WebClient webClient, OffloadMeters metrics) {
        this(properties, () -> webClient, metrics);
    }

    /**
     * Compatibility constructor for module consumers that still provide a bare meter registry.
     * The registry-backed path has no function lifecycle signal, so invocation admission is the
     * only safe point at which it can establish the legacy function registration.
     */
    public DefaultOffloadGateway(OffloadProperties properties, WebClient webClient,
                                 MeterRegistry meterRegistry) {
        this(properties, () -> webClient, () -> meterRegistry);
    }

    public DefaultOffloadGateway(OffloadProperties properties,
                                 Supplier<WebClient> webClient,
                                 OffloadMeters metrics) {
        this.properties = properties;
        this.webClient = webClient;
        this.metrics = metrics;
        this.legacyMeters = null;
    }

    /** Compatibility constructor retained for consumers that supply a registry lazily. */
    public DefaultOffloadGateway(OffloadProperties properties,
                                 Supplier<WebClient> webClient,
                                 Supplier<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.webClient = webClient;
        this.metrics = null;
        this.legacyMeters = new LegacyMeterLifecycle(meterRegistry);
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
        return invokeAtTarget(task,trigger,context,timeoutBudgetMs,targetUrl(task.functionSpec()),Map.of()).map(it.unimib.datai.nanofaas.controlplane.offload.PlannedRemoteResult::result);
    }
    @Override public Mono<it.unimib.datai.nanofaas.controlplane.offload.PlannedRemoteResult> invokePlannedRemote(InvocationTask task,it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute route,OffloadContext context,int budget) {
        if(context.offloadedHop() || route.kind()!=it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute.Kind.REMOTE) return Mono.error(new OffloadFailedException(route.targetUrl(),false,"second hop or invalid planned route"));
        return invokeAtTarget(task,OffloadTrigger.EAGER,context,budget,route.targetUrl(),route.headers());
    }
    private Mono<it.unimib.datai.nanofaas.controlplane.offload.PlannedRemoteResult> invokeAtTarget(InvocationTask task,OffloadTrigger trigger,OffloadContext context,int timeoutBudgetMs,String target,Map<String,String> nativeHeaders) {

        String uri = target + "/v1/functions/" + task.functionName() + ":invoke";
        long timeoutMs = Math.max(1, timeoutBudgetMs - TIMEOUT_MARGIN_MS);
        OffloadMeters.OffloadMeterLease meterLease = metrics == null
                ? null : metrics.offloadMeters(task.functionName(), trigger);
        LegacyMeterLease legacyLease = legacyMeters == null
                ? null : legacyMeters.lease(task.functionName(), trigger);

        WebClient.RequestBodySpec request = webClient.get().post().uri(uri);
        applyHopHeaders(request, task, context);
        nativeHeaders.forEach(request::header);
        forwardApplicationHeaders(request, task.request().headers());

        return request.bodyValue(task.request())
                .exchangeToMono(response -> {
                    String node=response.headers().asHttpHeaders().getFirst(it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy.EXECUTION_NODE_HEADER);
                    String attributed=node!=null && !node.isBlank() && node.length()<=256?node:null;
                    return readRemoteResponse(response,target,task.functionName()).map(result->new it.unimib.datai.nanofaas.controlplane.offload.PlannedRemoteResult(result,attributed));
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
                .doOnSubscribe(_ -> subscribed(meterLease, legacyLease))
                .doOnError(OffloadFailedException.class, _ -> failed(meterLease, legacyLease))
                .doFinally(_ -> close(meterLease, legacyLease));
    }

    private static void applyHopHeaders(WebClient.RequestBodySpec request, InvocationTask task, OffloadContext context) {
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
    }

    private Mono<InvocationResult> readRemoteResponse(ClientResponse response, String target, String functionName) {
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
                    "function '" + functionName + "' not registered on remote " + target)));
        }
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new OffloadFailedException(target, false,
                        REMOTE_PREFIX + target + " returned " + status
                                + (body.isBlank() ? "" : ": " + body))));
    }

    private static void subscribed(OffloadMeters.OffloadMeterLease meterLease, LegacyMeterLease legacyLease) {
        if (meterLease != null) {
            meterLease.subscribed();
        } else {
            legacyLease.subscribed();
        }
    }

    private static void failed(OffloadMeters.OffloadMeterLease meterLease, LegacyMeterLease legacyLease) {
        if (meterLease != null) {
            meterLease.failed();
        } else {
            legacyLease.failed();
        }
    }

    private static void close(OffloadMeters.OffloadMeterLease meterLease, LegacyMeterLease legacyLease) {
        if (meterLease != null) {
            meterLease.close();
        } else {
            legacyLease.close();
        }
    }

    int legacyOwnerCount() {
        return legacyMeters == null ? 0 : legacyMeters.ownerCount();
    }

    /** Compatibility metrics live exactly as long as real legacy subscriptions. */
    private static final class LegacyMeterLifecycle {
        private static final String FUNCTION_TAG = "function";
        private final Supplier<MeterRegistry> registry;
        private final Map<String, LegacyMeterOwner> owners = new HashMap<>();

        private LegacyMeterLifecycle(Supplier<MeterRegistry> registry) {
            this.registry = registry;
        }

        private LegacyMeterLease lease(String function, OffloadTrigger trigger) {
            return new LegacyMeterLease(this, function, trigger);
        }

        private synchronized LegacyMeterOwner retain(String function, OffloadTrigger trigger) {
            LegacyMeterOwner owner = owners.computeIfAbsent(function,
                    ignored -> new LegacyMeterOwner(registry.get(), function));
            owner.retained++;
            owner.offload(trigger).increment();
            return owner;
        }

        private synchronized void failed(LegacyMeterOwner owner) {
            if (owners.get(owner.function) == owner) {
                owner.failure().increment();
            }
        }

        private synchronized void release(LegacyMeterOwner owner) {
            if (--owner.retained == 0 && owners.remove(owner.function, owner)) {
                owner.removeMeters();
            }
        }

        private synchronized int ownerCount() {
            return owners.size();
        }
    }

    private static final class LegacyMeterLease implements AutoCloseable {
        private final LegacyMeterLifecycle lifecycle;
        private final String function;
        private final OffloadTrigger trigger;
        private LegacyMeterOwner owner;

        private LegacyMeterLease(LegacyMeterLifecycle lifecycle, String function, OffloadTrigger trigger) {
            this.lifecycle = lifecycle;
            this.function = function;
            this.trigger = trigger;
        }

        private void subscribed() {
            if (owner == null) {
                owner = lifecycle.retain(function, trigger);
            }
        }

        private void failed() {
            if (owner != null) {
                lifecycle.failed(owner);
            }
        }

        @Override
        public void close() {
            if (owner != null) {
                LegacyMeterOwner retainedOwner = owner;
                owner = null;
                lifecycle.release(retainedOwner);
            }
        }
    }

    private static final class LegacyMeterOwner {
        private final MeterRegistry registry;
        private final String function;
        private final Map<OffloadTrigger, Counter> offloads = new EnumMap<>(OffloadTrigger.class);
        private final List<Meter.Id> meterIds = new ArrayList<>();
        private int retained;
        private Counter failure;

        private LegacyMeterOwner(MeterRegistry registry, String function) {
            this.registry = registry;
            this.function = function;
        }

        private Counter offload(OffloadTrigger trigger) {
            return offloads.computeIfAbsent(trigger, ignored -> {
                Counter counter = Counter.builder("nanofaas.offload")
                        .tag(LegacyMeterLifecycle.FUNCTION_TAG, function)
                        .tag("trigger", trigger.name().toLowerCase(Locale.ROOT))
                        .register(registry);
                meterIds.add(counter.getId());
                return counter;
            });
        }

        private Counter failure() {
            if (failure == null) {
                failure = Counter.builder("nanofaas.offload.failure")
                        .tag(LegacyMeterLifecycle.FUNCTION_TAG, function)
                        .register(registry);
                meterIds.add(failure.getId());
            }
            return failure;
        }

        private void removeMeters() {
            meterIds.forEach(registry::remove);
        }
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
            if (key.startsWith("x-nanofaas-offload-") || key.equals("x-nanofaas-execution-node")
                    || key.equals("x-nanofaas-terminal-execution-id") || EXCLUDED_FORWARD_HEADERS.contains(key)
                    || HOP_BY_HOP_HEADERS.contains(key)
                    || key.startsWith(PROXY_HEADER_PREFIX)) {
                return;
            }
            request.header(name, value);
        });
    }

    private InvocationResult toResult(InvocationResponse response) {
        InvocationResult result = switch (response.status() == null ? "" : response.status()) {
            case "success" -> InvocationResult.successWithEnvelope(
                    response.output(), response.statusCode(), response.headers(), response.encoding());
            case "timeout" -> InvocationResult.error("REMOTE_TIMEOUT", "remote execution timed out");
            default -> response.error() != null
                    ? new InvocationResult(false, null, response.error())
                    : InvocationResult.error("REMOTE_ERROR", "remote execution failed with status " + response.status());
        };
        // The origin owns its response executionId. Keep the independently assigned
        // remote ID available for physical runtime proof, including failed handlers.
        var headers = new java.util.LinkedHashMap<String, String>();
        if (result.headers() != null) {
            result.headers().forEach((name, value) -> {
                if (!"X-NanoFaaS-Terminal-Execution-Id".equalsIgnoreCase(name)) {
                    headers.put(name, value);
                }
            });
        }
        String terminalId = response.executionId();
        if (terminalId != null && TERMINAL_EXECUTION_ID.matcher(terminalId).matches()) {
            headers.put("X-NanoFaaS-Terminal-Execution-Id", terminalId);
        }
        return new InvocationResult(result.success(), result.output(), result.error(),
                result.statusCode(), java.util.Map.copyOf(headers), result.encoding());
    }

}
