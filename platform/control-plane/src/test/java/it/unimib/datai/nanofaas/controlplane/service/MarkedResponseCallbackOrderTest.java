package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.dispatch.*;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class MarkedResponseCallbackOrderTest {
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void httpAndCallbackPublishTheSameOutputInBothOrders(boolean callbackFirst) throws Exception {
        var requestStarted=new CountDownLatch(1);var releaseResponse=new CountDownLatch(1);var httpFinished=new CountDownLatch(1);
        try(var server=new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    requestStarted.countDown();
                    if(!releaseResponse.await(5,TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return new MockResponse().setResponseCode(202).setBody("\"hello\"")
                            .addHeader("Content-Type","text/plain").addHeader("X-NanoFaaS-Function-Status","true");
                }
            });server.start();
            var store=new ExecutionStore();var metrics=new Metrics(new SimpleMeterRegistry());
            var runtime=TestWaiterCapacity.runtime(store,new IdempotencyStore(),metrics,"fn");
            var external=new ExternalDispatcher(WebClient.create()) {
                @Override public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                    return super.dispatch(task).whenComplete((result,error)->httpFinished.countDown());
                }
            };
            var handler=new ExecutionCompletionHandler(store,null,new DispatcherRouter(new LocalDispatcher(),external),metrics);
            var coordinator=new ReactiveInvocationCoordinator(null,metrics,null,null,handler,new InvocationResponseMapper(),runtime.waiters());
            var spec=new FunctionSpec("fn","img",List.of(),Map.of(),null,5000,1,10,0,server.url("/invoke").toString(),ExecutionMode.EXTERNAL,null,null,null);
            var request=new InvocationRequest("p",Map.of());
            var lookup=runtime.factory().createOrReuseExecution("fn",spec,request,"key",null,InvocationKind.SYNC);
            var waiting=coordinator.invoke(lookup,spec,5000).toFuture();
            var callback=InvocationResult.successWithEnvelope("hello",202,Map.of("Content-Type","text/plain"),null);
            try {
                assertThat(requestStarted.await(2,TimeUnit.SECONDS)).isTrue();
                String id=lookup.executionRecord().executionId();
                if(callbackFirst) handler.completeExecution(id,callback);
                releaseResponse.countDown();
                var answer=waiting.get(3,TimeUnit.SECONDS).response();
                if(!callbackFirst) handler.completeExecution(id,callback);
                assertThat(httpFinished.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(answer.output()).isEqualTo("hello");assertThat(answer.statusCode()).isEqualTo(202);
                assertThat(answer.headers()).containsEntry("Content-Type","text/plain");
                var replay=coordinator.invoke(runtime.factory().createOrReuseExecution("fn",spec,request,"key",null,InvocationKind.SYNC),spec,5000).block();
                assertThat(replay.response().output()).isEqualTo("hello");
                assertThat(replay.response().statusCode()).isEqualTo(answer.statusCode());
                // The shared answer is published before the terminal record is removed.
                await().atMost(2,TimeUnit.SECONDS).untilAsserted(() -> assertThat(store.inFlightCount()).isZero());
            } finally { releaseResponse.countDown();waiting.cancel(true); }
        }
    }
}
