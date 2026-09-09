package it.unimib.datai.nanofaas.modules.offload;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReviewOffloadWaiterBudgetTest {
    @Test
    void firstWaitersBudgetMustNotBecomeTheSharedOffloadDeadline() {
        VirtualTimeScheduler virtual = VirtualTimeScheduler.create();
        VirtualTimeScheduler.set(virtual);
        try {
            var remote = Sinks.<ClientResponse>one();
            var registry = new SimpleMeterRegistry();
            var gateway = new DefaultOffloadGateway(new OffloadProperties(true, "http://remote", true),
                    WebClient.builder().exchangeFunction(request -> remote.asMono()).build(), registry);
            var store = new ExecutionStore();
            var metrics = new Metrics(registry);
            var factory = new InvocationExecutionFactory(store, new IdempotencyStore(), metrics);
            var handler = new ExecutionCompletionHandler(store, null, mock(DispatcherRouter.class), metrics);
            var coordinator = new ReactiveInvocationCoordinator(null, metrics, null, gateway,
                    handler, new InvocationResponseMapper());
            var spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null, 10000,
                    1, 100, 0, null, ExecutionMode.LOCAL, null, null, null, null,
                    new OffloadPolicy(true, "http://remote", "always"));
            var request = new InvocationRequest("payload", Map.of());
            var first = factory.createOrReuseExecution("fn", spec, request, "key", null, InvocationKind.SYNC);
            var shortWaiter = coordinator.invoke(first, spec, 100).toFuture();
            var second = factory.createOrReuseExecution("fn", spec, request, "key", null, InvocationKind.SYNC);
            var longWaiter = coordinator.invoke(second, spec, 10000).toFuture();
            virtual.advanceTimeBy(Duration.ofMillis(60));
            System.out.println("OFFLOAD_WAITER_BUDGET function=10000 short=100 elapsed=60 longDone="
                    + longWaiter.isDone() + " sharedState=" + first.executionRecord().state());
            assertThat(longWaiter.isDone()).isFalse();
            assertThat(first.executionRecord().isTerminal()).isFalse();
            virtual.advanceTimeBy(Duration.ofMillis(60));
            assertThat(shortWaiter.join().response().status()).isEqualTo("timeout");
            assertThat(longWaiter).isNotDone();
            remote.tryEmitValue(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"executionId\":\"remote-id\",\"status\":\"success\",\"output\":\"done\"}")
                    .build());
            assertThat(longWaiter.join().response().output()).isEqualTo("done");
            assertThat(store.inFlightCount()).isZero();
        } finally { VirtualTimeScheduler.reset(); }
    }
}
