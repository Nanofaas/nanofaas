package it.unimib.datai.nanofaas.modules.offload;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultOffloadGatewayTest {

    private static final int BUDGET_MS = 5000;

    private MockWebServer server;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private DefaultOffloadGateway gateway(OffloadProperties props) {
        return new DefaultOffloadGateway(props, WebClient.create(), meterRegistry);
    }

    private DefaultOffloadGateway gateway() {
        return gateway(new OffloadProperties(true, serverUrl(), true));
    }

    private String serverUrl() {
        String url = server.url("/").toString();
        return url.substring(0, url.length() - 1);
    }

    private static FunctionSpec spec(String name, OffloadPolicy offload, int timeoutMs) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                timeoutMs, 1, 10, 0, null, ExecutionMode.EXTERNAL, RuntimeMode.HTTP, null, null, null, offload);
    }

    private static InvocationTask task(FunctionSpec spec) {
        return new InvocationTask("exec-1", spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, "trace-1", Instant.now(), 1,
                InvocationKind.SYNC
            );
    }

    private static InvocationTask task(FunctionSpec spec, Map<String, String> requestHeaders) {
        return new InvocationTask("exec-1", spec.name(), spec,
                new InvocationRequest("payload", Map.of(), requestHeaders), null, "trace-1", Instant.now(), 1,
                InvocationKind.SYNC
            );
    }

    private static MockResponse successEnvelope() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"remote-1\",\"status\":\"success\",\"output\":\"out\",\"error\":null}");
    }

    private static OffloadFailedException offloadFailure(Throwable ex) {
        assertThat(ex).isInstanceOf(OffloadFailedException.class);
        return (OffloadFailedException) ex;
    }

    private static void invokeRemoteBlocking(DefaultOffloadGateway gateway, InvocationTask task,
                                             OffloadTrigger trigger, OffloadContext ctx, int budgetMs) {
        gateway.invokeRemote(task, trigger, ctx, budgetMs).block();
    }

    @Test
    void successResponseMapsToSuccessResultAndForwardsHeaders() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"remote-1\",\"status\":\"success\",\"output\":\"out\",\"error\":null}"));
        FunctionSpec spec = spec("echo", null, 5000);

        InvocationResult result = gateway()
                .invokeRemote(task(spec), OffloadTrigger.EAGER, new OffloadContext(false, "00-abc-def-01", "vendor=1"), BUDGET_MS)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("out");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/functions/echo:invoke");
        assertThat(recorded.getHeader("X-NanoFaaS-Offload-Hop")).isEqualTo("1");
        assertThat(recorded.getHeader("X-Trace-Id")).isEqualTo("trace-1");
        assertThat(recorded.getHeader("traceparent")).isEqualTo("00-abc-def-01");
        assertThat(recorded.getHeader("tracestate")).isEqualTo("vendor=1");
        assertThat(meterRegistry.counter("nanofaas.offload", "function", "echo", "trigger", "eager").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("nanofaas.offload.failure", "function", "echo").count())
                .isEqualTo(0.0);
    }

    @Test
    void remote404ErrorsWithOffloadFailedAndClearMessage() {
        server.enqueue(new MockResponse().setResponseCode(404));
        FunctionSpec spec = spec("ghost", null, 5000);

        DefaultOffloadGateway gateway = gateway();
        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.EAGER, context, BUDGET_MS))
                .isInstanceOf(OffloadFailedException.class)
                .hasMessageContaining("not registered on remote");
        assertThat(meterRegistry.counter("nanofaas.offload.failure", "function", "ghost").count())
                .isEqualTo(1.0);
    }

    @Test
    void remote5xxErrorsWithOffloadFailed() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("saturated"));
        FunctionSpec spec = spec("busy", null, 5000);

        DefaultOffloadGateway gateway = gateway();
        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.DEPTH, context, BUDGET_MS))
                .isInstanceOf(OffloadFailedException.class)
                .hasMessageContaining("503");
    }

    @Test
    void unreachableRemoteErrorsWithOffloadFailed() throws IOException {
        String url = serverUrl();
        server.shutdown();
        FunctionSpec spec = spec("down", null, 2000);
        DefaultOffloadGateway gateway = gateway(new OffloadProperties(true, url, true));

        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.EAGER, context, BUDGET_MS))
                .isInstanceOf(OffloadFailedException.class)
                .matches(ex -> !offloadFailure(ex).gatewayTimeout());
    }

    @Test
    void slowRemoteErrorsWithGatewayTimeoutFlavor() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"r\",\"status\":\"success\",\"output\":null,\"error\":null}")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));
        FunctionSpec spec = spec("slow", null, 5000);

        DefaultOffloadGateway gateway = gateway();
        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.EAGER, context, 300))
                .isInstanceOf(OffloadFailedException.class)
                .matches(ex -> offloadFailure(ex).gatewayTimeout());
    }

    @Test
    void empty2xxBodyErrorsWithOffloadFailed() {
        server.enqueue(new MockResponse().setResponseCode(200));
        FunctionSpec spec = spec("mute", null, 5000);

        DefaultOffloadGateway gateway = gateway();
        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.EAGER, context, BUDGET_MS))
                .isInstanceOf(OffloadFailedException.class)
                .hasMessageContaining("empty response body");
    }

    @Test
    void remoteErrorStatusKeepsRemoteErrorInfo() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"r\",\"status\":\"error\",\"output\":null,\"error\":{\"code\":\"BOOM\",\"message\":\"kaputt\"}}"));
        FunctionSpec spec = spec("boom", null, 5000);

        InvocationResult result = gateway()
                .invokeRemote(task(spec), OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("BOOM");
    }

    @Test
    void invokeRemote_functionDecidedNon2xx_isNotAnOffloadFailure() {
        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-NanoFaaS-Function-Status", "true")
                .setBody("{\"executionId\":\"ex-1\",\"status\":\"success\",\"output\":{\"error\":\"bad\"},"
                        + "\"error\":null,\"statusCode\":422}"));
        FunctionSpec spec = spec("picky", null, 5000);

        InvocationResult result = gateway()
                .invokeRemote(task(spec), OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(422);
    }

    @Test
    void invokeRemote_functionDecided404_isNotReportedAsNotRegistered() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-NanoFaaS-Function-Status", "true")
                .setBody("{\"executionId\":\"ex-2\",\"status\":\"success\",\"output\":{\"error\":\"missing\"},"
                        + "\"error\":null,\"statusCode\":404}"));
        FunctionSpec spec = spec("picky404", null, 5000);

        InvocationResult result = gateway()
                .invokeRemote(task(spec), OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(404);
    }

    @Test
    void invokeRemote_2xx_propagatesEnvelopeFields() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"ex-3\",\"status\":\"success\",\"output\":\"created\","
                        + "\"error\":null,\"statusCode\":201,\"headers\":{\"Location\":\"/x\"}}"));
        FunctionSpec spec = spec("created", null, 5000);

        InvocationResult result = gateway()
                .invokeRemote(task(spec), OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.headers()).containsEntry("Location", "/x");
    }

    @Test
    void invokeRemote_non2xxWithoutMarker_isStillOffloadFailure() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        FunctionSpec spec = spec("unmarked500", null, 5000);

        DefaultOffloadGateway gateway = gateway();
        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.EAGER, context, BUDGET_MS))
                .isInstanceOf(OffloadFailedException.class)
                .hasMessageContaining("500");
    }

    @Test
    void invokeRemote_404WithoutMarker_stillReportsNotRegistered() {
        server.enqueue(new MockResponse().setResponseCode(404));
        FunctionSpec spec = spec("unmarked404", null, 5000);

        DefaultOffloadGateway gateway = gateway();
        InvocationTask invocationTask = task(spec);
        OffloadContext context = OffloadContext.none();
        assertThatThrownBy(() -> invokeRemoteBlocking(gateway, invocationTask, OffloadTrigger.EAGER, context, BUDGET_MS))
                .isInstanceOf(OffloadFailedException.class)
                .hasMessageContaining("not registered on remote");
    }

    @Test
    void decisions() {
        OffloadProperties props = new OffloadProperties(true, "http://cloud:8080", true);
        DefaultOffloadGateway gateway = gateway(props);

        FunctionSpec noPolicy = spec("a", null, 1000);
        FunctionSpec always = spec("b", new OffloadPolicy(null, null, "always"), 1000);
        FunctionSpec optedOut = spec("c", new OffloadPolicy(false, null, "always"), 1000);
        FunctionSpec customTarget = spec("d", new OffloadPolicy(null, "http://other:9090/", null), 1000);

        assertThat(gateway.enabled()).isTrue();
        assertThat(gateway.shouldOffloadEagerly(noPolicy)).isFalse();
        assertThat(gateway.shouldOffloadEagerly(always)).isTrue();
        assertThat(gateway.shouldOffloadEagerly(optedOut)).isFalse();
        assertThat(gateway.shouldOffloadOnPressure(noPolicy)).isTrue();
        assertThat(gateway.shouldOffloadOnPressure(optedOut)).isFalse();
        assertThat(gateway.targetUrl(noPolicy)).isEqualTo("http://cloud:8080");
        // trailing slash normalized, per-function override wins
        assertThat(gateway.targetUrl(customTarget)).isEqualTo("http://other:9090");

        DefaultOffloadGateway disabled = gateway(new OffloadProperties(false, "http://cloud:8080", true));
        assertThat(disabled.enabled()).isFalse();

        DefaultOffloadGateway noPressure = gateway(new OffloadProperties(true, "http://cloud:8080", false));
        assertThat(noPressure.shouldOffloadOnPressure(noPolicy)).isFalse();
    }

    @Test
    void perFunctionTargetAloneActivatesOffload() {
        DefaultOffloadGateway noGlobalTarget = gateway(new OffloadProperties(true, null, true));
        FunctionSpec withOwnTarget = spec("own", new OffloadPolicy(null, "http://edge2:8080", "always"), 1000);
        FunctionSpec withoutTarget = spec("bare", new OffloadPolicy(null, null, "always"), 1000);

        assertThat(noGlobalTarget.enabled()).isTrue();
        assertThat(noGlobalTarget.shouldOffloadEagerly(withOwnTarget)).isTrue();
        assertThat(noGlobalTarget.targetUrl(withOwnTarget)).isEqualTo("http://edge2:8080");
        // no target anywhere: never offload, even under pressure
        assertThat(noGlobalTarget.shouldOffloadEagerly(withoutTarget)).isFalse();
        assertThat(noGlobalTarget.shouldOffloadOnPressure(withoutTarget)).isFalse();
    }

    @Test
    void invokeRemote_forwardsApplicationHeadersAsRealHttpHeaders() throws InterruptedException {
        server.enqueue(successEnvelope());
        FunctionSpec spec = spec("echo", null, 5000);

        gateway().invokeRemote(task(spec, Map.of(
                        "x-tenant", "acme",
                        "X-Request-Id", "req-42")),
                OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS).block();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("x-tenant")).isEqualTo("acme");
        assertThat(recorded.getHeader("X-Request-Id")).isEqualTo("req-42");
    }

    @Test
    void invokeRemote_contentCodingHeadersDescribeTheEnvelopeAndAreNotForwarded() throws InterruptedException {
        server.enqueue(successEnvelope());
        FunctionSpec spec = spec("echo", null, 5000);

        // Same class as content-type/content-length: these describe the caller's original
        // body, not the JSON envelope this hop actually sends. Forwarding content-encoding
        // labels a plain envelope as compressed, and accept-encoding invites a compressed
        // response the offload client is not built to decode.
        gateway().invokeRemote(task(spec, Map.of(
                        "x-tenant", "acme",
                        "content-encoding", "gzip",
                        "accept-encoding", "gzip, br",
                        "expect", "100-continue")),
                OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS).block();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("content-encoding")).isNull();
        assertThat(recorded.getHeader("expect")).isNull();
        assertThat(recorded.getHeaders().values("accept-encoding")).doesNotContain("gzip, br");
        assertThat(recorded.getHeader("x-tenant")).isEqualTo("acme");
    }

    @Test
    void invokeRemote_reservedHeadersCannotOverrideGatewayOwnedHeaders() throws InterruptedException {
        server.enqueue(successEnvelope());
        FunctionSpec spec = spec("guarded", null, 5000);

        // The envelope carries reserved names the gateway itself manages. None may
        // reach the wire: the receiving control plane would bind them to dedicated
        // parameters, so a forged value would corrupt the offload-hop marker or the
        // trace context (re-offload prevention / tracing acceptance).
        gateway().invokeRemote(task(spec, Map.of(
                        "x-tenant", "acme",
                        "x-nanofaas-offload-hop", "forged-hop",
                        "x-trace-id", "forged-trace",
                        "traceparent", "00-forged-parent-01",
                        "tracestate", "forged=vendor",
                        "content-type", "text/plain",
                        "content-length", "999",
                        "host", "forged-host")),
                OffloadTrigger.EAGER, new OffloadContext(false, "00-real-parent-01", "real=vendor"), BUDGET_MS).block();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeaders().values("X-NanoFaaS-Offload-Hop")).containsExactly("1");
        assertThat(recorded.getHeaders().values("X-Trace-Id")).containsExactly("trace-1");
        assertThat(recorded.getHeaders().values("traceparent")).containsExactly("00-real-parent-01");
        assertThat(recorded.getHeaders().values("tracestate")).containsExactly("real=vendor");
        // the application header still crosses, and the transport Content-Type keeps
        // describing the JSON envelope rather than the forged text/plain value
        assertThat(recorded.getHeader("x-tenant")).isEqualTo("acme");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");
        assertThat(recorded.getHeader("Content-Length")).isNotEqualTo("999");
        assertThat(recorded.getHeader("Host")).isNotEqualTo("forged-host");
    }

    @Test
    void invokeRemote_hopByHopHeadersAreNotForwarded() throws InterruptedException {
        server.enqueue(successEnvelope());
        FunctionSpec spec = spec("hop", null, 5000);

        // proxy-*, upgrade, trailer and te are hop-by-hop per-connection semantics that the
        // receiving controller does NOT strip from the envelope, so on a real path they reach
        // the gateway map and must not leak onto the second hop. (Headers nominated by the
        // caller's Connection field are excluded upstream by InvocationController and covered
        // by the controller/E2E tests, not here.)
        gateway().invokeRemote(task(spec, Map.of(
                        "x-tenant", "acme",
                        "proxy-authorization", "Basic Zm9yZ2Vk",
                        "proxy-connection", "keep-alive",
                        "upgrade", "h2c",
                        "trailer", "X-Trailer",
                        "te", "trailers")),
                OffloadTrigger.EAGER, OffloadContext.none(), BUDGET_MS).block();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("x-tenant")).isEqualTo("acme");
        assertThat(recorded.getHeader("Proxy-Authorization")).isNull();
        assertThat(recorded.getHeader("Proxy-Connection")).isNull();
        assertThat(recorded.getHeader("Upgrade")).isNull();
        assertThat(recorded.getHeader("Trailer")).isNull();
        assertThat(recorded.getHeader("TE")).isNull();
    }
}
