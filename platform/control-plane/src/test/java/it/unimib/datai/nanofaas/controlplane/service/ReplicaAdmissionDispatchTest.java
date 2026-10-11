package it.unimib.datai.nanofaas.controlplane.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.QueuedInputLease;
import it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.PlannedInvocationRoute;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReplicaAdmissionDispatchTest {
    @Test
    void routedAndQueuedOldEpochCannotExecuteAfterSameWarmReplicaResumes() throws Exception {
        var calls = new AtomicInteger();
        var backend = backend(calls);
        try (var proxy = new RoundRobinFunctionProxyFactory("127.0.0.1").create("f")) {
            proxy.enablePhysicalSlots();
            proxy.updateBackends(List.of("http://127.0.0.1:" + backend.getAddress().getPort()));
            var initial = proxy.beginQuiesce();
            assertThat(proxy.resume(initial, initial.admissionEpoch())).isTrue();
            var spec = spec(proxy.endpointUrl());
            var metrics = new Metrics(new SimpleMeterRegistry());
            var runtime = TestWaiterCapacity.runtime(new ExecutionStore(), new IdempotencyStore(), metrics, "f");
            var lookup = runtime.factory().createOrReuseExecution("f", spec,
                    new InvocationRequest(Map.of("value", 1), Map.of()), null, null, InvocationKind.SYNC);
            var gateway = mock(OffloadGateway.class);
            when(gateway.planRoute(any(), any())).thenReturn(PlannedInvocationRoute.local("edge", initial.admissionEpoch()));
            var queue = mock(SyncQueueGateway.class);
            when(queue.enabled()).thenReturn(true);
            var queued = new AtomicReference<InvocationTask>();
            doAnswer(call -> { queued.set(call.getArgument(0)); return null; }).when(queue).enqueueOrThrow(any());
            var coordinator = new ReactiveInvocationCoordinator(null, metrics, queue, gateway,
                    mock(ExecutionCompletionHandler.class), new InvocationResponseMapper(), runtime.waiters());
            var response = coordinator.invoke(lookup, spec, 5000).toFuture();
            assertThat(queued.get()).isNotNull();
            assertThat(queued.get().replicaAdmissionEpoch()).isEqualTo(initial.admissionEpoch());
            var successor = proxy.beginQuiesce();
            assertThat(proxy.awaitQuiesced(Duration.ofSeconds(1))).isTrue();
            assertThat(proxy.resume(successor, successor.admissionEpoch())).isTrue();
            var dispatcher = new ExternalDispatcher(WebClient.create());
            try (var input = lookup.executionRecord().openPhysicalInput(queued.get())) {
                var rejected = dispatcher.dispatch(input.task()).get(5, TimeUnit.SECONDS);
                assertThat(rejected.result().success()).isFalse();
                assertThat(rejected.result().statusCode()).isEqualTo(503);
                assertThat(rejected.handlerExecuted()).isFalse();
                lookup.executionRecord().completion().complete(rejected.result());
            } finally {
                queued.get().releaseQueuedInput();
            }
            assertThat(response.get(5, TimeUnit.SECONDS).response().status()).isEqualTo("error");
            assertThat(calls.get()).isZero();
            var fresh = task(spec, "fresh", 1).withReplicaAdmissionEpoch(successor.admissionEpoch());
            assertThat(dispatcher.dispatch(fresh).get(5, TimeUnit.SECONDS).result().success()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            verify(gateway, never()).invokeRemote(any(), any(), any(), anyInt());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void inputMaterializationPreservesPinnedEpochAndDispatchOwnership() {
        var metrics = new Metrics(new SimpleMeterRegistry());
        var runtime = TestWaiterCapacity.runtime(new ExecutionStore(), new IdempotencyStore(), metrics, "f");
        var lookup = runtime.factory().createOrReuseExecution("f", spec("http://proxy"),
                new InvocationRequest(Map.of("numbers", List.of(1, 2, 3)), Map.of()), null, null, InvocationKind.SYNC);
        var record = lookup.executionRecord();
        record.pinPlannedRoute(PlannedInvocationRoute.local("edge", "issued"));
        var queued = record.prepareForQueue();
        var ownership = mock(DispatchOwnership.class);
        try (var physical = record.openPhysicalInput(queued.withDispatchLease(ownership))) {
            assertThat(physical.task().request()).isNotSameAs(queued.request());
            assertThat(physical.task().request().input()).isEqualTo(Map.of("numbers", List.of(1, 2, 3)));
            assertThat(physical.task().replicaAdmissionEpoch()).isEqualTo("issued");
            assertThat(physical.task().dispatchLease()).isSameAs(ownership);
        } finally {
            queued.releaseQueuedInput();
        }
    }

    @Test
    void retryCannotReplaceTheLogicalExecutionsPinnedEpoch() {
        var spec = spec("http://proxy");
        var record = new ExecutionRecord("work", task(spec, "work", 1));
        record.pinPlannedRoute(PlannedInvocationRoute.local("edge", "issued"));
        var releaseCount = new AtomicInteger();
        var queuedLease = new QueuedInputLease(releaseCount::incrementAndGet);
        var ownership = mock(DispatchOwnership.class);
        var copy = record.task().withQueuedInputLease(queuedLease).withDispatchLease(ownership);
        assertThat(copy.replicaAdmissionEpoch()).isEqualTo("issued");
        assertThat(copy.queuedInputLease()).isSameAs(queuedLease);
        assertThat(copy.dispatchLease()).isSameAs(ownership);
        copy.releaseQueuedInput();
        copy.releaseQueuedInput();
        assertThat(releaseCount.get()).isEqualTo(1);
        record.markRunning();
        record.resetForRetry(task(spec, "work", 2).withReplicaAdmissionEpoch("other-epoch"));
        assertThat(record.task().replicaAdmissionEpoch()).isEqualTo("issued");
    }

    @Test
    void publicRequestHeadersAndClientDefaultsCannotSupplyThePrivateEpochPermit() throws Exception {
        var calls = new AtomicInteger();
        var backend = backend(calls);
        try (var proxy = new RoundRobinFunctionProxyFactory("127.0.0.1").create("f")) {
            proxy.enablePhysicalSlots();
            proxy.updateBackends(List.of("http://127.0.0.1:" + backend.getAddress().getPort()));
            var token = proxy.beginQuiesce();
            assertThat(proxy.resume(token, token.admissionEpoch())).isTrue();
            var request = new InvocationRequest("value", Map.of(), Map.of("X-NanoFaaS-Replica-Epoch", token.admissionEpoch()));
            var task = new InvocationTask("unfenced", "f", spec(proxy.endpointUrl()), request, null, null, Instant.now(), 1, InvocationKind.SYNC);
            var client = WebClient.builder().defaultHeader("X-NanoFaaS-Replica-Epoch", token.admissionEpoch()).build();
            var rejected = new ExternalDispatcher(client).dispatch(task).get(5, TimeUnit.SECONDS);
            assertThat(rejected.result().statusCode()).isEqualTo(503);
            assertThat(rejected.handlerExecuted()).isFalse();
            assertThat(calls.get()).isZero();
        } finally {
            backend.stop(0);
        }
    }

    private static InvocationTask task(FunctionSpec spec, String id, int attempt) {
        return new InvocationTask(id, "f", spec, new InvocationRequest("value", Map.of()), null, null, Instant.now(), attempt, InvocationKind.SYNC);
    }

    private static FunctionSpec spec(String url) {
        return new FunctionSpec("f", "image", List.of(), Map.of("NANOFAAS_ONE_SHOT_PROFILE", "true"),
                null, 5000, 1, 10, 0, url, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null);
    }

    private static HttpServer backend(AtomicInteger calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runtime/status", e -> reply(e, "{\"schemaVersion\":1,\"incarnation\":\"warm\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}"));
        server.createContext("/invoke", e -> { calls.incrementAndGet(); reply(e, "{\"value\":1}"); });
        server.createContext("/runtime/executions/", e -> {
            String id = e.getRequestURI().getPath().substring("/runtime/executions/".length());
            reply(e, "{\"state\":\"RELEASED\",\"incarnation\":\"warm\",\"executionId\":\"" + id + "\",\"dispatchAttempt\":\"1\",\"occupancySeconds\":0.01,\"handlerStarted\":true}");
        });
        server.start();
        return server;
    }

    private static void reply(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
