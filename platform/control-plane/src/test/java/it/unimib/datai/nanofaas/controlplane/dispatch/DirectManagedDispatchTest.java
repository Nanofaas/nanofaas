package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DirectManagedDispatchTest {
    @Test void resolvedEndpointReusesEnvelopeAndHeadersWithoutChangingTheSpec() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"value\":7}")
                    .addHeader("X-NanoFaaS-Function-Status", "true").addHeader("X-Cold-Start", "true"));
            server.start();
            var target = new Target(server.url("/invoke").toString());
            var task = task(1000);
            var result = new ExternalDispatcher(WebClient.create()).dispatch(task, target).get(2, TimeUnit.SECONDS);
            var received = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(received.getHeader("X-Execution-Id")).isEqualTo("execution");
            assertThat(received.getHeader("X-Dispatch-Attempt")).isEqualTo("2");
            assertThat(received.getHeader("X-Trace-Id")).isEqualTo("trace");
            assertThat(received.getHeader("Idempotency-Key")).isEqualTo("key");
            assertThat(result.result().success()).isTrue(); assertThat(result.coldStart()).isTrue();
            assertThat(task.functionSpec().endpointUrl()).isEqualTo("http://unused.invalid/invoke");
            awaitClosed(target); assertThat(target.sent.get()).isEqualTo(1);
        }
    }
    @Test void providerEvidenceWinsOverUntrustedResponseHeaders() throws Exception {
        for (boolean evidence : new boolean[]{false, true}) {
            try (var server = new MockWebServer()) {
                server.enqueue(new MockResponse().setResponseCode(503).setBody("failed")
                        .addHeader("Retry-After", "2").addHeader("X-NanoFaaS-Handler-Executed", "true")); server.start();
                var target = new Target(server.url("/invoke").toString()); target.evidence = evidence;
                var result = new ExternalDispatcher(WebClient.create()).dispatch(task(1000), target).get(2, TimeUnit.SECONDS);
                assertThat(result.handlerExecuted()).isEqualTo(evidence);
                assertThat(result.retryNotBefore()).isNotNull(); assertThat(result.result().success()).isFalse();
                awaitClosed(target);
            }
        }
    }
    @Test void synchronousTransportSetupFailureClosesUnsentReservation() throws Exception {
        var target = new Target("not a valid URI");
        var result = new ExternalDispatcher(WebClient.create()).dispatch(task(1000), target).get(2, TimeUnit.SECONDS);
        assertThat(result.result().success()).isFalse(); awaitClosed(target); assertThat(target.sent.get()).isZero();
    }
    @Test void timeoutDuringBodyAndCallerCancellationCloseExactlyOnce() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            try (var server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody("{\"value\":1}").addHeader("Content-Type", "application/json")
                        .setBodyDelay(1, TimeUnit.SECONDS)); server.start();
                var target = new Target(server.url("/invoke").toString());
                var future = new ExternalDispatcher(WebClient.create()).dispatch(task(cancel ? 2000 : 100), target);
                assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
                if (cancel) future.cancel(true); else assertThat(future.get(2, TimeUnit.SECONDS).result().success()).isFalse();
                awaitClosed(target); assertThat(target.sent.get()).isEqualTo(1);
            }
        }
    }
    private static void awaitClosed(Target target) throws Exception {
        assertThat(target.closed.await(1, TimeUnit.SECONDS)).isTrue(); assertThat(target.closes.get()).isEqualTo(1);
    }
    private static InvocationTask task(int timeout) {
        var spec = new FunctionSpec("fn", "image", null, Map.of("NANOFAAS_ONE_SHOT_PROFILE", "true"), null,
                timeout, 2, 10, 3, "http://unused.invalid/invoke", ExecutionMode.DEPLOYMENT, null, null, null);
        return new InvocationTask("execution", "fn", spec, new InvocationRequest("input", null), "key", "trace", Instant.now(), 2, InvocationKind.SYNC);
    }
    private static final class Target implements ManagedInvocationTarget {
        final String endpoint; final AtomicInteger sent = new AtomicInteger(); final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1); boolean evidence;
        Target(String endpoint) { this.endpoint = endpoint; }
        public String endpointUrl() { return endpoint; }
        public String replicaId() { return "replica"; }
        public boolean markDispatched() { sent.incrementAndGet(); return true; }
        public CompletionStage<Boolean> executionEvidence() { return CompletableFuture.completedFuture(evidence); }
        public void close() { closes.incrementAndGet(); closed.countDown(); }
    }
}
