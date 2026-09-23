package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.config.DispatchConnectionPool;
import it.unimib.datai.nanofaas.controlplane.config.HttpClientConfig;
import it.unimib.datai.nanofaas.controlplane.config.HttpClientProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcher;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class P12CrossHostAdmissionTest {

    @Test
    void perDestinationPoolsDoNotBypassP07AggregateExecutionInputOrWaiterAdmission() throws Exception {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("host-a", 1);
        generations.register("host-b", 1);
        BlockingDispatcher firstBackend = new BlockingDispatcher();
        try (MockWebServer firstHost = server(firstBackend);
             MockWebServer secondHost = new MockWebServer();
             DispatchConnectionPool pool = new DispatchConnectionPool(properties(), new SimpleMeterRegistry())) {
            secondHost.enqueue(new MockResponse().setBody("{}"));
            secondHost.start();
            ExecutionStore store = new ExecutionStore();
            Metrics metrics = new Metrics(new SimpleMeterRegistry());
            InvocationCapacity capacity = new InvocationCapacity(
                    generations, 1, 1, 1_000_000, 1_000_000, 16);
            InvocationExecutionFactory factory = new InvocationExecutionFactory(
                    store, new IdempotencyStore(), metrics, capacity,
                    new RetainedInputEstimator.Limits(12, 128, 1_024, 64 * 1_024));
            ExternalDispatcher external = new ExternalDispatcher(new HttpClientConfig().webClient(
                    WebClient.builder(), properties(), pool.provider()));
            ExecutionCompletionHandler completion = new ExecutionCompletionHandler(
                    store, null, new DispatcherRouter(new LocalDispatcher(), external),
                    metrics, null, generations);

            FunctionSpec first = spec("host-a", firstHost.url("/invoke").toString());
            var admitted = factory.createOrReuseExecution(
                    first.name(), first, new InvocationRequest("payload", Map.of()),
                    null, null, InvocationKind.SYNC);
            admitted.publishAdmission();
            completion.dispatchDirect(admitted.executionRecord().task());
            firstBackend.awaitRequest();

            FunctionSpec second = spec("host-b", secondHost.url("/invoke").toString());
            String secondName = second.name();
            var request = new InvocationRequest("payload", Map.of());
            assertThatThrownBy(() -> factory.createOrReuseExecution(
                    secondName, second, request, null, null, InvocationKind.SYNC))
                    .isInstanceOf(InvocationQuotaExceededException.class)
                    .extracting("resource")
                    .isEqualTo(InvocationQuotaExceededException.Resource.EXECUTION);
            assertThat(capacity.executionReservedGlobally()).isOne();
            assertThat(secondHost.getRequestCount()).isZero();
            assertThat(pool.destinationCount()).isOne();

            firstBackend.release();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(capacity.executionReservedGlobally()).isZero();
                assertThat(capacity.inputReservedGlobally()).isZero();
            });

            InvocationCapacity inputCapacity = new InvocationCapacity(
                    generations, 10, 10, 100, 100, 16);
            InvocationCapacity.Admission inputOwner = inputCapacity.reserve("host-a", "input-a", 80);
            inputOwner.publish();
            assertThatThrownBy(() -> inputCapacity.reserve("host-b", "input-b", 30))
                    .isInstanceOf(InvocationQuotaExceededException.class)
                    .extracting("resource")
                    .isEqualTo(InvocationQuotaExceededException.Resource.INPUT);
            inputOwner.rollback();

            WaiterCapacity waiters = new WaiterCapacity(generations, 1, 1);
            try (var _ = waiters.reserve("host-a", "waiter-a")) {
                assertThatThrownBy(() -> waiters.reserve("host-b", "waiter-b"))
                        .isInstanceOf(InvocationQuotaExceededException.class)
                        .extracting("resource")
                        .isEqualTo(InvocationQuotaExceededException.Resource.WAITER);
            }
            assertThat(waiters.reservedGlobally()).isZero();
        }
    }

    private static HttpClientProperties properties() {
        return new HttpClientProperties(
                1_000, 10_000, 1, 1, 2, 45_000,
                5_000, 0, 50, 50, 5_000);
    }

    private static FunctionSpec spec(String name, String endpoint) {
        return new FunctionSpec(name, "image", List.of(), Map.of(), null,
                10_000, 1, 10, 0, endpoint, ExecutionMode.EXTERNAL, null, null, null);
    }

    private static MockWebServer server(Dispatcher dispatcher) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(dispatcher);
        server.start();
        return server;
    }

    private static final class BlockingDispatcher extends Dispatcher {
        private final CountDownLatch request = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public MockResponse dispatch(RecordedRequest ignored) throws InterruptedException {
            request.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                return new MockResponse().setResponseCode(504);
            }
            return new MockResponse().setHeader("Content-Type", "application/json").setBody("{}");
        }

        void awaitRequest() throws InterruptedException {
            assertThat(request.await(3, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }
    }
}
