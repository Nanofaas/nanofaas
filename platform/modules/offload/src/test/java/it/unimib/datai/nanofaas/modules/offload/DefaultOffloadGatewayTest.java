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
                new InvocationRequest("payload", Map.of()), null, "trace-1", Instant.now(), 1);
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
}
