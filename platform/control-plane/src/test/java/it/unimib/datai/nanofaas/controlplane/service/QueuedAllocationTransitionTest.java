package it.unimib.datai.nanofaas.controlplane.service;

import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;
import it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcher;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QueuedAllocationTransitionTest {
    @Test
    void acceptedLocalWorkExecutesOnceWithTheCurrentAllocation() throws Exception {
        var calls = new AtomicInteger();
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/runtime/status", exchange -> {
            var bytes = "{\"schemaVersion\":1,\"incarnation\":\"warm\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backend.createContext("/invoke", exchange -> {
            calls.incrementAndGet();
            var bytes = "\"executed\"".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backend.createContext("/runtime/executions/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/runtime/executions/".length());
            var bytes = ("{\"state\":\"RELEASED\",\"incarnation\":\"warm\",\"executionId\":\"" + id
                    + "\",\"dispatchAttempt\":\"1\",\"occupancySeconds\":0.01,\"handlerStarted\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        backend.start();
        try (var proxy = new RoundRobinFunctionProxyFactory("127.0.0.1").create("f")) {
            proxy.enablePhysicalSlots();
            proxy.updateBackends(List.of("http://127.0.0.1:" + backend.getAddress().getPort()));
            var initial = proxy.beginQuiesce();
            assertThat(proxy.resume(initial, initial.admissionEpoch())).isTrue();
            var spec = new FunctionSpec("f", "image", List.of(), Map.of("NANOFAAS_ONE_SHOT_PROFILE", "true"), null, 5000, 1, 10, 0,
                    proxy.endpointUrl(), ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null);
            var metrics = new Metrics(new SimpleMeterRegistry());
            var runtime = TestWaiterCapacity.runtime(new ExecutionStore(), new IdempotencyStore(), metrics, "f");
            var lookup = runtime.factory().createOrReuseExecution("f", spec,
                    new InvocationRequest(Map.of("value", 1), Map.of()), null, null, InvocationKind.SYNC);
            var gateway = mock(OffloadGateway.class);
            when(gateway.planRoute(any(), any())).thenReturn(PlannedInvocationRoute.local("edge"));
            var queue = mock(SyncQueueGateway.class);
            when(queue.enabled()).thenReturn(true);
            var queued = new AtomicReference<InvocationTask>();
            doAnswer(call -> { queued.set(call.getArgument(0)); return null; }).when(queue).enqueueOrThrow(any());
            var coordinator = new ReactiveInvocationCoordinator(null, metrics, queue, gateway,
                    mock(ExecutionCompletionHandler.class), new InvocationResponseMapper(), runtime.waiters());
            var response = coordinator.invoke(lookup, spec, 5000).toFuture();
            assertThat(queued.get()).isNotNull();
            var successor = proxy.beginQuiesce();
            assertThat(proxy.awaitQuiesced(Duration.ofSeconds(1))).isTrue();
            assertThat(proxy.resume(successor, successor.admissionEpoch())).isTrue();
            try (var input = lookup.executionRecord().openPhysicalInput(queued.get())) {
                var result = new ExternalDispatcher(WebClient.create()).dispatch(input.task()).get(5, TimeUnit.SECONDS);
                assertThat(result.result().success()).isTrue();
                lookup.executionRecord().completion().complete(result.result());
            } finally {
                queued.get().releaseQueuedInput();
            }
            assertThat(response.get(5, TimeUnit.SECONDS).response().status()).isEqualTo("success");
            assertThat(calls.get()).isEqualTo(1);
            verify(gateway, times(1)).planRoute(any(), any());
            verify(gateway, never()).invokeRemote(any(), any(), any(), anyInt());
        } finally {
            backend.stop(0);
        }
    }
}
