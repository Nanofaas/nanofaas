package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.ExternalDispatcher;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P06: attempt-scoped capacity leases and local cancellation. These tests exercise the
 * core direct path (no queue module) and the transport boundary, not a terminal record
 * alone.
 */
class DispatchLifecycleAndCancellationTest {

    private static FunctionSpec spec(String name, int concurrency) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10_000, concurrency, 100, 0, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static FunctionSpec externalSpec(String name, String endpoint, int timeoutMs) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                timeoutMs, 1, 100, 0, endpoint, ExecutionMode.EXTERNAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
    }

    private static ExecutionStore shortLivedStore() {
        return new ExecutionStore(
                ExecutionStoreProperties.of(Duration.ofMinutes(5), Duration.ofMillis(100),
                        Duration.ofSeconds(30), 100_000),
                new SimpleMeterRegistry());
    }

    @Test
    void directAdmissionAtConcurrencyOneAdmitsExactlyOneOfOneHundred() {
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                new DispatcherRouter(new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return backend;
                    }
                }, null), metrics);
        TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(
                store, new IdempotencyStore(), metrics, "fn");
        ReactiveInvocationCoordinator coordinator =
                new ReactiveInvocationCoordinator(null, metrics, null, null, handler,
                        new InvocationResponseMapper(), runtime.waiters());
        InvocationExecutionFactory factory = runtime.factory();

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 100; i++) {
            InvocationExecutionFactory.ExecutionLookup lookup = factory.createOrReuseExecution(
                    "fn", spec("fn", 1), new InvocationRequest("payload", Map.of()),
                    null, null, InvocationKind.SYNC);
            CompletableFuture<SyncInvocation> attempt =
                    coordinator.invoke(lookup, spec("fn", 1), 10_000).toFuture();
            if (attempt.isCompletedExceptionally()) {
                rejected++;
            } else {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(rejected).isEqualTo(99);
        assertThat(store.inFlightCount()).isEqualTo(1);
        backend.complete(DispatchResult.warm(InvocationResult.success("done")));
    }

    @Test
    void directDispatchAcquiresAndReleasesItsLeaseExactlyOnce() {
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        DispatcherRouter router = new DispatcherRouter(new LocalDispatcher(), null);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null, router, metrics);

        InvocationTask task = task("e1", spec("fn", 1));
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        handler.dispatchDirect(task);
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.completion()).isDone();
    }

    @Test
    void nonInterruptibleLocalHandlerKeepsItsSlotUntilTheWorkEnds() {
        ExecutionStore store = new ExecutionStore();
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        ExecutorService retryExecutor = it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport
                .newBoundedExecutor("test-retry-held-slot", 2, 4, 32);

        AtomicInteger dispatches = new AtomicInteger();
        CompletableFuture<DispatchResult> handlerWork = new CompletableFuture<>();
        LocalDispatcher local = new LocalDispatcher() {
            @Override
            public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                dispatches.incrementAndGet();
                return handlerWork; // the non-interruptible handler: never ends until we say so
            }
        };

        // Production wiring: the core-only retry enqueuer shares the capacity registry and
        // dispatches the immutable task with its ownership handle, so a retry must re-acquire a slot.
        ExecutionCompletionHandler[] holder = new ExecutionCompletionHandler[1];
        ExecutorBackedInvocationEnqueuer enqueuer = new ExecutorBackedInvocationEnqueuer(
                task -> holder[0].dispatch(task), capacity, retryExecutor);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, enqueuer::enqueue,
                new DispatcherRouter(local, null), metrics, null, capacity);
        holder[0] = handler;

        // timeoutMs=100 so the attempt deadline fires; concurrency=1; maxRetries=1.
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                100, 1, 100, 1, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);

        try {
            handler.dispatchDirect(task);
            assertThat(record.state()).isEqualTo(ExecutionState.RUNNING);
            assertThat(dispatches.get()).isEqualTo(1);

            // The attempt deadline fires and the retry policy runs, but the retry must not run
            // a second handler while the first is still going: the lease is held, so the retry
            // cannot acquire a fresh slot (acceptance "no path bypasses the cap").
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(record.completion()).isDone());
            assertThat(dispatches.get())
                    .as("a retry must not run a second handler while the first still runs")
                    .isEqualTo(1);
            assertThat(capacity.inFlight("fn"))
                    .as("the still-running handler keeps its slot")
                    .isEqualTo(1);

            // The handler finally ends: the raw work's completion releases the lease.
            handlerWork.complete(DispatchResult.warm(InvocationResult.success("done")));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(capacity.inFlight("fn")).isZero());
        } finally {
            retryExecutor.shutdownNow();
        }
    }

    @Test
    void administrativeExpiryCancelsTheRawTransportAndReleasesTheLease() {
        ExecutionStore store = shortLivedStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        CompletableFuture<DispatchResult> neverCompletes = new CompletableFuture<>();
        DispatcherRouter router = org.mockito.Mockito.mock(DispatcherRouter.class);
        org.mockito.Mockito.when(router.dispatchExternal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(neverCompletes);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null, router, metrics);

        InvocationTask task = task("exec-stuck", externalSpec("fn", "http://unused/invoke", 10_000));
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        handler.dispatchDirect(task);
        assertThat(record.state()).isEqualTo(ExecutionState.RUNNING);

        // The administrative expiry must cancel the real transport handle (the raw future)
        // and conclude the waiter. Disposing local HTTP resources does not promise
        // that the remote function has stopped.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.outcomeOf("exec-stuck")).isNotNull();
            assertThat(record.completion()).isDone();
        });
        assertThat(neverCompletes).isCancelled();
        assertThat(record.completion().join().success()).isFalse();
    }

    @Test
    void cancellingTheRawHttpFutureDisposesTheTransportSubscription() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{\"ok\":true}").setHeadersDelay(300, TimeUnit.MILLISECONDS));
        server.start();
        try {
            String endpoint = server.url("/invoke").toString();
            ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
            CompletableFuture<DispatchResult> future =
                    dispatcher.dispatch(task("exec-http", externalSpec("fn", endpoint, 10_000)));

            // The HTTP request actually went out over the wire.
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();

            // Cancelling the raw Mono.toFuture() disposes the underlying HTTP subscription:
            // this is the transport-boundary cancellation, not a terminal record.
            future.cancel(true);
            assertThat(future).isCancelled();
        } finally {
            server.shutdown();
        }
    }

    @Test
    void administrativeExpiryCancelsTheHttpTransportAndArchivesTheOutcome() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{\"ok\":true}").setHeadersDelay(300, TimeUnit.MILLISECONDS));
        server.start();
        try {
            String endpoint = server.url("/invoke").toString();
            ExecutionStore store = shortLivedStore();
            Metrics metrics = new Metrics(new SimpleMeterRegistry());
            ExternalDispatcher external = new ExternalDispatcher(WebClient.builder().build());
            ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                    new DispatcherRouter(new LocalDispatcher(), external), metrics);

            InvocationTask task = task("exec-http", externalSpec("fn", endpoint, 10_000));
            ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
            store.put(record);
            handler.dispatchDirect(task);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(store.outcomeOf("exec-http")).isNotNull());
            // The request reached the wire before the administrative expiry cancelled it.
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        } finally {
            server.shutdown();
        }
    }

    @Test
    void doubleCallbackReleasesCapacityOnce() {
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        LocalDispatcher local = new LocalDispatcher() {
            @Override
            public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                return backend;
            }
        };
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                new DispatcherRouter(local, null), metrics);

        InvocationTask task = task("exec-double", spec("fn", 1));
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        handler.dispatchDirect(task);
        backend.complete(DispatchResult.warm(InvocationResult.success("ok")));
        handler.completeExecution("exec-double", DispatchResult.warm(InvocationResult.success("late")));
        handler.completeExecution("exec-double", DispatchResult.warm(InvocationResult.success("later")));
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.completion().join().success()).isTrue();
    }

    @Test
    void leaseReleaseIsIdempotentAndFencedByGeneration() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("fn", 1);
        DispatchLease lease = registry.tryAcquireLease("fn", 1);
        assertThat(lease).isNotNull();
        assertThat(registry.inFlight("fn")).isEqualTo(1);
        lease.release();
        lease.release(); // idempotent
        assertThat(registry.inFlight("fn")).isZero();
    }

    @Test
    void retiredLeaseDoesNotDecrementTheNewRegistration() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("fn", 1);
        DispatchLease oldLease = registry.tryAcquireLease("fn", 1);
        assertThat(oldLease).isNotNull();

        registry.remove("fn");
        registry.register("fn", 1);
        DispatchLease newLease = registry.tryAcquireLease("fn", 1);
        assertThat(newLease).isNotNull();
        assertThat(registry.inFlight("fn")).isEqualTo(1);

        oldLease.release(); // drains the retired generation, never the new one
        assertThat(registry.inFlight("fn")).isEqualTo(1);
        newLease.release();
        assertThat(registry.inFlight("fn")).isZero();
    }
}
