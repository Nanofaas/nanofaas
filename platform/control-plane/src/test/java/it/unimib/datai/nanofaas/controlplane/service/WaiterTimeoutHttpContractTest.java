package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.api.InvocationController;
import it.unimib.datai.nanofaas.controlplane.dispatch.*;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.offload.*;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WaiterTimeoutHttpContractTest {
    private static FunctionSpec spec() {
        return new FunctionSpec("fn", "img", List.of(), Map.of(), null, 10_000, 1, 10, 0,
                null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void waiterTimeoutReturns408WithoutFunctionStatusMarker(boolean offloaded) throws Exception {
        var store=new ExecutionStore();var keys=new IdempotencyStore();
        var metrics=new Metrics(new SimpleMeterRegistry());
        var runtime=TestWaiterCapacity.runtime(store,keys,metrics,"fn");
        var started=new CountDownLatch(1);
        var backend=new CompletableFuture<DispatchResult>();
        var remote=Sinks.<InvocationResult>one();
        var handler=new ExecutionCompletionHandler(store,null,new DispatcherRouter(new LocalDispatcher() {
            @Override public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                started.countDown();return backend;
            }
        },null),metrics);
        OffloadGateway gateway=offloaded?new OffloadGateway() {
            public boolean enabled() { return true; }
            public boolean shouldOffloadEagerly(FunctionSpec s) { return true; }
            public boolean shouldOffloadOnPressure(FunctionSpec s) { return false; }
            public String targetUrl(FunctionSpec s) { return "http://remote"; }
            public Mono<InvocationResult> invokeRemote(InvocationTask task,OffloadTrigger trigger,OffloadContext context,int budget) {
                return Mono.defer(()->{started.countDown();return remote.asMono();});
            }
        }:null;
        var mapper=new InvocationResponseMapper();
        var coordinator=new ReactiveInvocationCoordinator(null,metrics,null,gateway,handler,mapper,runtime.waiters());
        var functions=mock(FunctionService.class);when(functions.get("fn")).thenReturn(Optional.of(spec()));
        var service=new InvocationService(functions,null,store,metrics,handler,runtime.factory(),mapper,coordinator);
        var client=WebTestClient.bindToController(new InvocationController(service)).build();
        var first=runtime.factory().createOrReuseExecution("fn",spec(),new InvocationRequest("p",Map.of()),"shared",null,InvocationKind.SYNC);
        var owner=coordinator.invoke(first,spec(),10_000).toFuture();
        try {
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();
            first.executionRecord().attributeExecutionNode("worker-a");
            String id=first.executionRecord().executionId();
            client.post().uri("/v1/functions/fn:invoke").header("Idempotency-Key","shared").header("X-Timeout-Ms","10")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(new InvocationRequest("p",Map.of())).exchange()
                    .expectStatus().isEqualTo(408).expectHeader().valueEquals("X-Execution-Id",id)
                    .expectHeader().valueEquals("X-NanoFaaS-Execution-Node","worker-a")
                    .expectHeader().doesNotExist("X-NanoFaaS-Function-Status")
                    .expectBody().jsonPath("$.status").isEqualTo("timeout").jsonPath("$.executionId").isEqualTo(id)
                    .jsonPath("$.waiterTimedOut").doesNotExist();
            assertThat(first.executionRecord().isTerminal()).isFalse();
            if(!offloaded) assertThat(mapper.toStatus(first.executionRecord()).status()).isEqualTo("running");
            assertThat(store.getOrNull(id)).isSameAs(first.executionRecord());
            assertThat(owner).isNotDone();
            var result=InvocationResult.success("callback answer");
            if(offloaded) handler.completeOffloadedExecution(id,result);else handler.completeExecution(id,result);
            assertThat(owner.get(2,TimeUnit.SECONDS).response().output()).isEqualTo("callback answer");
            client.post().uri("/v1/functions/fn:invoke").header("Idempotency-Key","shared")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(new InvocationRequest("p",Map.of())).exchange()
                    .expectStatus().isOk().expectHeader().valueEquals("X-Execution-Id",id)
                    .expectBody().jsonPath("$.status").isEqualTo("success").jsonPath("$.output").isEqualTo("callback answer");
            assertThat(service.getStatus(id).orElseThrow().status()).isEqualTo("success");
        } finally {
            backend.complete(DispatchResult.warm(InvocationResult.success("late HTTP")));
            remote.tryEmitValue(InvocationResult.success("late HTTP"));owner.cancel(true);
        }
    }

    @Test
    void timeoutExceptionFromSharedExecutionIsAnExecutionError() {
        var store=new ExecutionStore();var metrics=new Metrics(new SimpleMeterRegistry());
        var runtime=TestWaiterCapacity.runtime(store,new IdempotencyStore(),metrics,"fn");
        var lookup=runtime.factory().createOrReuseExecution("fn",spec(),new InvocationRequest("p",Map.of()),null,null,InvocationKind.SYNC);
        lookup.executionRecord().completion().completeExceptionally(new TimeoutException("backend timed out"));
        var coordinator=new ReactiveInvocationCoordinator(null,metrics,null,null,mock(ExecutionCompletionHandler.class),new InvocationResponseMapper(),runtime.waiters());
        var answer=coordinator.invoke(lookup,spec(),1000).block(Duration.ofSeconds(2));
        assertThat(answer.response().status()).isEqualTo("error");
        assertThat(answer.response().error().code()).isEqualTo("EXECUTION_FAILED");
    }
}
