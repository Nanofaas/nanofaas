package it.unimib.datai.nanofaas.controlplane.service;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class P07cReviewFixTest {
    private static final RetainedInputEstimator.Limits INPUT_LIMITS =
            new RetainedInputEstimator.Limits(12, 128, 1_024, 64 * 1_024);

    @Test
    void cancellationBeforeDeploymentWakeUpKeepsPhysicalInputUntilReadinessReallyDrains() {
        AtomicLong tickerNanos = new AtomicLong();
        Fixture fixture = new Fixture(tickerNanos, 1_000_000);
        CompletableFuture<Void> readiness = new CompletableFuture<>();
        DeploymentWakeUpGate gate = mock(DeploymentWakeUpGate.class);
        when(gate.ensureReady(any())).thenReturn(readiness);
        DispatcherRouter router = mock(DispatcherRouter.class);
        ExecutionCompletionHandler handler = fixture.handler(router, gate);
        ExecutionRecord record = fixture.newLookup(deploymentSpec()).executionRecord();
        long canonicalBytes = fixture.capacity.inputReservedGlobally();

        handler.dispatchDirect(record.task());
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes);
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isEqualTo(canonicalBytes);

        fixture.expire(record);

        assertThat(fixture.capacity.inputReservedGlobally())
                .as("the pending readiness callback still captures the physical request")
                .isEqualTo(canonicalBytes);
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isEqualTo(canonicalBytes);
        verify(router, never()).dispatchExternal(any());

        readiness.complete(null);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(fixture.capacity.inputReservedGlobally()).isZero());
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isZero();
        verify(router, never()).dispatchExternal(any());
    }

    @Test
    void canceledDeploymentWaitsForANonCooperativeTransportToReallyComplete() {
        AtomicLong tickerNanos = new AtomicLong();
        Fixture fixture = new Fixture(tickerNanos, 1_000_000);
        DeploymentWakeUpGate gate = mock(DeploymentWakeUpGate.class);
        when(gate.ensureReady(any())).thenReturn(CompletableFuture.completedFuture(null));
        NonCooperativeFuture<DispatchResult> transport = new NonCooperativeFuture<>();
        DispatcherRouter router = mock(DispatcherRouter.class);
        when(router.dispatchExternal(any())).thenReturn(transport);
        ExecutionCompletionHandler handler = fixture.handler(router, gate);
        ExecutionRecord record = fixture.newLookup(deploymentSpec()).executionRecord();
        long canonicalBytes = fixture.capacity.inputReservedGlobally();

        handler.dispatchDirect(record.task());
        fixture.expire(record);

        assertThat(transport.cancelRequested).isTrue();
        assertThat(fixture.capacity.inputReservedGlobally())
                .as("logical cancellation is not proof that transport stopped retaining the request")
                .isEqualTo(canonicalBytes);
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isEqualTo(canonicalBytes);

        transport.complete(DispatchResult.warm(InvocationResult.success("late")));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(fixture.capacity.inputReservedGlobally()).isZero());
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isZero();
    }

    @Test
    void canceledOffloadKeepsPhysicalInputUntilTheRawRemotePublisherCompletes() {
        AtomicLong tickerNanos = new AtomicLong();
        Fixture fixture = new Fixture(tickerNanos, 1_000_000);
        NonCooperativeFuture<InvocationResult> remote = new NonCooperativeFuture<>();
        OffloadGateway gateway = mock(OffloadGateway.class);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.shouldOffloadEagerly(any())).thenReturn(true);
        when(gateway.targetUrl(any())).thenReturn("http://remote");
        when(gateway.invokeRemote(any(), any(), any(), anyInt())).thenReturn(Mono.fromFuture(remote));
        ExecutionCompletionHandler handler = fixture.handler(mock(DispatcherRouter.class), null);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, fixture.metrics, null, gateway, handler, new InvocationResponseMapper(), fixture.waiters());
        FunctionSpec spec = offloadSpec();
        InvocationExecutionFactory.ExecutionLookup lookup = fixture.newLookup(spec);
        ExecutionRecord record = lookup.executionRecord();
        long canonicalBytes = fixture.capacity.inputReservedGlobally();

        coordinator.invoke(lookup, spec, 10_000, OffloadContext.none()).subscribe();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes);
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isEqualTo(canonicalBytes);

        fixture.expire(record);

        assertThat(fixture.capacity.inputReservedGlobally())
                .as("canceling the local outcome must not masquerade as remote drain")
                .isEqualTo(canonicalBytes);
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isEqualTo(canonicalBytes);

        remote.complete(InvocationResult.success("late"));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(fixture.capacity.inputReservedGlobally()).isZero());
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isZero();
    }

    @Test
    void directPhysicalCopySaturationPropagatesOverloadAndRollsBackAdmission() {
        InvocationRequest request = request();
        long canonicalBytes = ((CanonicalInvocationInput.Accepted)
                CanonicalInvocationInput.canonicalize(request, INPUT_LIMITS)).retainedBytes();
        Fixture fixture = new Fixture(new AtomicLong(), canonicalBytes * 2, canonicalBytes - 1);
        DispatcherRouter router = new DispatcherRouter(new LocalDispatcher() {
            @Override
            public CompletableFuture<DispatchResult> dispatch(
                    it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask task) {
                return CompletableFuture.completedFuture(
                        DispatchResult.warm(InvocationResult.success("must-not-run")));
            }
        }, null);
        ExecutionCompletionHandler handler = fixture.handler(router, null);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, fixture.metrics, null, null, handler, new InvocationResponseMapper(), fixture.waiters());
        FunctionSpec spec = localSpec();
        InvocationExecutionFactory.ExecutionLookup lookup = fixture.newLookup(spec);

        assertThatThrownBy(() -> coordinator.invoke(
                lookup, spec, 10_000, OffloadContext.none()).block())
                .isInstanceOf(InvocationQuotaExceededException.class)
                .extracting("resource")
                .isEqualTo(InvocationQuotaExceededException.Resource.INPUT);
        assertThat(fixture.store.getOrNull(lookup.executionRecord().executionId())).isNull();
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
        assertThat(fixture.capacity.physicalInputCopyReservedGlobally()).isZero();
    }

    @Test
    void synchronousDispatchErrorRollsBackAdmissionAndBothQuotaDimensions() {
        Fixture fixture = new Fixture(new AtomicLong(), 1_000_000);
        DispatcherRouter router = new DispatcherRouter(new LocalDispatcher() {
            @Override
            public CompletableFuture<DispatchResult> dispatch(
                    it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask task) {
                throw new AssertionError("dispatcher failed");
            }
        }, null);
        ExecutionCompletionHandler handler = fixture.handler(router, null);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, fixture.metrics, null, null, handler, new InvocationResponseMapper(), fixture.waiters());
        FunctionSpec spec = localSpec();
        InvocationExecutionFactory.ExecutionLookup lookup = fixture.newLookup(spec);

        assertThatThrownBy(() -> coordinator.invoke(
                lookup, spec, 10_000, OffloadContext.none()).block())
                .isInstanceOf(AssertionError.class)
                .hasMessage("dispatcher failed");
        assertThat(fixture.store.getOrNull(lookup.executionRecord().executionId())).isNull();
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void lateOldGenerationOffloadDoesNotUpdateReplacementMetrics() {
        Fixture fixture = new Fixture(new AtomicLong(), 1_000_000);
        NonCooperativeFuture<InvocationResult> remote = new NonCooperativeFuture<>();
        OffloadGateway gateway = mock(OffloadGateway.class);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.shouldOffloadEagerly(any())).thenReturn(true);
        when(gateway.targetUrl(any())).thenReturn("http://remote");
        when(gateway.invokeRemote(any(), any(), any(), anyInt())).thenReturn(Mono.fromFuture(remote));
        ExecutionCompletionHandler handler = fixture.handler(mock(DispatcherRouter.class), null);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, fixture.metrics, null, gateway, handler, new InvocationResponseMapper(), fixture.waiters());
        FunctionSpec spec = offloadSpec();
        InvocationExecutionFactory.ExecutionLookup lookup = fixture.newLookup(spec);

        coordinator.invoke(lookup, spec, 10_000, OffloadContext.none()).subscribe();
        fixture.generations.remove("fn");
        fixture.metrics.removeFunction("fn");
        fixture.generations.register("fn", 1);
        fixture.metrics.registerFunction("fn");

        remote.complete(InvocationResult.success("late"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(lookup.executionRecord().completion()).isDone());
        assertThat(fixture.meterRegistry.get("function_success_total")
                .tag("function", "fn").counter().count()).isZero();
        assertThat(fixture.meterRegistry.get("function_error_total")
                .tag("function", "fn").counter().count()).isZero();
    }

    @Test
    void lateOldGenerationOffloadFailureDoesNotUpdateReplacementMetrics() {
        Fixture fixture = new Fixture(new AtomicLong(), 1_000_000);
        CompletableFuture<InvocationResult> remote = new CompletableFuture<>();
        OffloadGateway gateway = mock(OffloadGateway.class);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.shouldOffloadEagerly(any())).thenReturn(true);
        when(gateway.targetUrl(any())).thenReturn("http://remote");
        when(gateway.invokeRemote(any(), any(), any(), anyInt())).thenReturn(Mono.fromFuture(remote));
        ExecutionCompletionHandler handler = fixture.handler(mock(DispatcherRouter.class), null);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, fixture.metrics, null, gateway, handler, new InvocationResponseMapper(), fixture.waiters());
        FunctionSpec spec = offloadSpec();
        InvocationExecutionFactory.ExecutionLookup lookup = fixture.newLookup(spec);

        coordinator.invoke(lookup, spec, 10_000, OffloadContext.none()).subscribe();
        fixture.generations.remove("fn");
        fixture.metrics.removeFunction("fn");
        fixture.generations.register("fn", 1);
        fixture.metrics.registerFunction("fn");

        remote.completeExceptionally(new it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException(
                "http://remote", false, "late failure"));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(lookup.executionRecord().completion()).isDone());
        assertThat(fixture.meterRegistry.get("function_success_total")
                .tag("function", "fn").counter().count()).isZero();
        assertThat(fixture.meterRegistry.get("function_error_total")
                .tag("function", "fn").counter().count()).isZero();
    }

    @Test
    void retryKeepsTheLogicalAdmissionsGenerationIdentity() {
        Fixture fixture = new Fixture(new AtomicLong(), 1_000_000);
        ExecutionRecord record = fixture.newLookup(localSpec()).executionRecord();
        var admittedGeneration = record.currentGeneration();
        InvocationTask first = record.task();
        InvocationTask retry = new InvocationTask(
                first.executionId(), first.functionName(), first.functionSpec(), first.request(),
                null, first.traceId(), first.enqueuedAt(), first.attempt() + 1, first.kind());

        record.resetForRetry(retry);

        assertThat(record.currentGeneration()).isEqualTo(admittedGeneration);
    }

    private static FunctionSpec deploymentSpec() {
        return new FunctionSpec("fn", "image", List.of(), Map.of(), null,
                60_000, 1, 10, 0, "http://deployment", ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP, null, null, null);
    }

    private static FunctionSpec offloadSpec() {
        return new FunctionSpec("fn", "image", List.of(), Map.of(), null,
                60_000, 1, 10, 0, null, ExecutionMode.LOCAL,
                RuntimeMode.HTTP, null, null, null,
                new OffloadPolicy(true, "http://remote", "always"));
    }

    private static FunctionSpec localSpec() {
        return new FunctionSpec("fn", "image", List.of(), Map.of(), null,
                60_000, 1, 10, 0, null, ExecutionMode.LOCAL,
                null, null, null, null);
    }

    private static InvocationRequest request() {
        return new InvocationRequest(new ArrayList<>(List.of("payload")), Map.of());
    }

    private static final class Fixture {
        private final AtomicLong tickerNanos;
        private final ExecutionStore store;
        private final FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        private final InvocationCapacity capacity;
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final Metrics metrics = new Metrics(meterRegistry, generations);
        private final InvocationExecutionFactory factory;

        private Fixture(AtomicLong tickerNanos, long inputBytes) {
            this(tickerNanos, inputBytes, inputBytes);
        }

        private Fixture(AtomicLong tickerNanos, long canonicalInputBytes, long physicalInputBytes) {
            this.tickerNanos = tickerNanos;
            this.store = new ExecutionStore(new ExecutionStoreProperties(
                    Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofSeconds(30),
                    100, 100, 11600), (Ticker) tickerNanos::get);
            generations.register("fn", 1);
            metrics.registerFunction("fn");
            capacity = new InvocationCapacity(
                    generations, 10, 10,
                    canonicalInputBytes, canonicalInputBytes,
                    physicalInputBytes, physicalInputBytes, 16);
            factory = new InvocationExecutionFactory(
                    store, new IdempotencyStore(), metrics, capacity, INPUT_LIMITS);
        }

        private ExecutionCompletionHandler handler(DispatcherRouter router, DeploymentWakeUpGate gate) {
            return new ExecutionCompletionHandler(store, null, router, metrics, gate, generations);
        }

        private WaiterCapacity waiters() {
            return new WaiterCapacity(generations, 10_000, 1_000);
        }

        private InvocationExecutionFactory.ExecutionLookup newLookup(FunctionSpec spec) {
            return factory.createOrReuseExecution("fn", spec,
                    request(),
                    null, null, InvocationKind.SYNC);
        }

        private void expire(ExecutionRecord record) {
            tickerNanos.set(Duration.ofMinutes(31).toNanos());
            store.inFlightCount();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(record.completion()).isDone());
        }
    }

    private static final class NonCooperativeFuture<T> extends CompletableFuture<T> {
        private boolean cancelRequested;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelRequested = true;
            return false;
        }
    }
}
