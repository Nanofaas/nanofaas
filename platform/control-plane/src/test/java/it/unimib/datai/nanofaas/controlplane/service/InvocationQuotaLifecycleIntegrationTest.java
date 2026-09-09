package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvocationQuotaLifecycleIntegrationTest {
    private static final RetainedInputEstimator.Limits INPUT_LIMITS =
            new RetainedInputEstimator.Limits(12, 128, 1_024, 64 * 1_024);

    @Test
    void newExecutionPublishesCanonicalInputAndTerminalStateWaitsForPhysicalDrain() {
        Fixture fixture = new Fixture(10, 1_000_000);
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("items", new ArrayList<>(java.util.List.of("a", "b")));

        var lookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest(source, Map.of()), null, "trace", InvocationKind.SYNC);
        ExecutionRecord record = lookup.executionRecord();
        long canonicalBytes = fixture.capacity.inputReservedGlobally();

        assertThat(fixture.capacity.executionReservedGlobally()).isOne();
        assertThat(canonicalBytes).isPositive();
        assertThat(record.task().request().input()).isInstanceOf(Object[].class).isNotSameAs(source);

        ExecutionRecord.PhysicalInput physical = record.openPhysicalInput(record.task());
        assertThat(physical.task().request().input()).isInstanceOf(Map.class).isNotSameAs(source);
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes * 2);

        record.markSuccess("ok");
        fixture.store.settle(record);
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes * 2);

        physical.close();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void replayAndRetryReuseTheExistingCanonicalReservation() {
        Fixture fixture = new Fixture(10, 1_000_000);
        InvocationRequest request = new InvocationRequest(
                new ArrayList<>(java.util.List.of("payload")), Map.of());
        var first = fixture.factory.createOrReuseExecution(
                "fn", spec(), request, "same", "trace-1", InvocationKind.SYNC);
        first.publishAdmission();
        long retained = fixture.capacity.inputReservedGlobally();

        var replay = fixture.factory.createOrReuseExecution(
                "fn", spec(), request, "same", "trace-2", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(fixture.capacity.executionReservedGlobally()).isOne();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(retained);

        ExecutionRecord record = first.executionRecord();
        var firstAttempt = record.openPhysicalInput(record.task());
        firstAttempt.close();
        var retryAttempt = record.openPhysicalInput(record.task());
        assertThat(fixture.capacity.executionReservedGlobally()).isOne();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(retained * 2);
        retryAttempt.close();

        record.markSuccess("ok");
        fixture.store.settle(record);
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void storePublicationFailureRollsBackBothReservations() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 1);
        InvocationCapacity capacity = new InvocationCapacity(generations, 10, 10, 1_000_000, 1_000_000, 16);
        ExecutionStore failingStore = new ExecutionStore() {
            @Override
            public void put(ExecutionRecord ignored) {
                throw new IllegalStateException("publication failed");
            }
        };
        InvocationExecutionFactory factory = new InvocationExecutionFactory(
                failingStore, new IdempotencyStore(), new Metrics(new SimpleMeterRegistry()),
                capacity, INPUT_LIMITS);

        assertThatThrownBy(() -> factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest(new ArrayList<>(java.util.List.of("x")), Map.of()),
                null, null, InvocationKind.SYNC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publication failed");
        assertThat(capacity.executionReservedGlobally()).isZero();
        assertThat(capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void enqueueAbandonmentRollsBackPublishedRecordReservations() {
        Fixture fixture = new Fixture(10, 1_000_000);
        var lookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest("payload", Map.of()), "key", null, InvocationKind.SYNC);

        assertThatThrownBy(() -> InvocationEnqueueSupport.admitIfNew(
                lookup, () -> { throw new IllegalStateException("queue failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
        assertThat(fixture.store.getOrNull(lookup.executionRecord().executionId())).isNull();
    }

    @Test
    void nonCooperativeLocalWorkKeepsInputChargedAfterAdministrativeTerminalUntilRawDrain() {
        Fixture fixture = new Fixture(10, 1_000_000);
        CompletableFuture<DispatchResult> rawWork = new CompletableFuture<>();
        LocalDispatcher local = new LocalDispatcher() {
            @Override
            public CompletableFuture<DispatchResult> dispatch(
                    it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask task) {
                assertThat(task.request().input()).isInstanceOf(java.util.List.class);
                return rawWork;
            }
        };
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                fixture.store, null, new DispatcherRouter(local, null),
                new Metrics(new SimpleMeterRegistry()), null, fixture.generations);
        var lookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest(new ArrayList<>(java.util.List.of("x")), Map.of()),
                null, null, InvocationKind.SYNC);
        ExecutionRecord record = lookup.executionRecord();
        long canonicalBytes = fixture.capacity.inputReservedGlobally();

        handler.dispatchDirect(record.task());
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes * 2);

        record.markTimeout();
        fixture.store.settle(record);
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes * 2);

        rawWork.complete(DispatchResult.warm(
                it.unimib.datai.nanofaas.common.model.InvocationResult.success("late")));
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void pendingOffloadKeepsInputChargedAfterAdministrativeTerminalUntilRemoteDrain() {
        Fixture fixture = new Fixture(10, 1_000_000);
        CompletableFuture<InvocationResult> remote = new CompletableFuture<>();
        OffloadGateway gateway = mock(OffloadGateway.class);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.shouldOffloadEagerly(any())).thenReturn(true);
        when(gateway.targetUrl(any())).thenReturn("http://remote");
        when(gateway.invokeRemote(any(), any(), any(), anyInt())).thenReturn(Mono.fromFuture(remote));
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                fixture.store, null, new DispatcherRouter(new LocalDispatcher(), null),
                new Metrics(new SimpleMeterRegistry()), null, fixture.generations);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, new Metrics(new SimpleMeterRegistry()), null, gateway, handler,
                new InvocationResponseMapper());
        var lookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest(new ArrayList<>(java.util.List.of("x")), Map.of()),
                null, null, InvocationKind.SYNC);
        ExecutionRecord record = lookup.executionRecord();
        long canonicalBytes = fixture.capacity.inputReservedGlobally();

        coordinator.invoke(lookup, spec(), 10_000, OffloadContext.none());
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes * 2);

        record.markTimeout();
        fixture.store.settle(record);
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(canonicalBytes * 2);

        remote.complete(InvocationResult.success("late"));
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void repeatedInputSaturationDoesNotGrowReservations() {
        InvocationRequest request = new InvocationRequest(
                new ArrayList<>(java.util.List.of("bounded-payload")), Map.of());
        long retained = ((it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput.Accepted)
                it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput.canonicalize(
                        request, INPUT_LIMITS)).retainedBytes();
        Fixture fixture = new Fixture(10, retained);
        var admitted = fixture.factory.createOrReuseExecution(
                "fn", spec(), request, null, null, InvocationKind.SYNC);

        for (int attempt = 0; attempt < 1_000; attempt++) {
            assertThatThrownBy(() -> fixture.factory.createOrReuseExecution(
                    "fn", spec(), request, null, null, InvocationKind.SYNC))
                    .isInstanceOf(InvocationQuotaExceededException.class)
                    .extracting("resource")
                    .isEqualTo(InvocationQuotaExceededException.Resource.INPUT);
        }
        assertThat(fixture.capacity.executionReservedGlobally()).isOne();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(retained);

        admitted.abandonAdmission();
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void oldGenerationPhysicalDrainCannotReleaseNewGenerationInput() {
        Fixture fixture = new Fixture(10, 1_000_000);
        InvocationRequest request = new InvocationRequest(
                new ArrayList<>(java.util.List.of("payload")), Map.of());
        var oldLookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), request, null, null, InvocationKind.SYNC);
        ExecutionRecord oldRecord = oldLookup.executionRecord();
        long oneCanonical = fixture.capacity.inputReservedGlobally();
        ExecutionRecord.PhysicalInput oldPhysical = oldRecord.openPhysicalInput(oldRecord.task());

        fixture.generations.remove("fn");
        fixture.generations.register("fn", 1);
        var currentLookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), request, null, null, InvocationKind.SYNC);
        ExecutionRecord currentRecord = currentLookup.executionRecord();

        oldRecord.markSuccess("old");
        fixture.store.settle(oldRecord);
        assertThat(fixture.capacity.executionReservedForFunction("fn")).isOne();
        assertThat(fixture.capacity.inputReservedForFunction("fn")).isEqualTo(oneCanonical * 3);

        oldPhysical.close();
        assertThat(fixture.capacity.executionReservedForFunction("fn")).isOne();
        assertThat(fixture.capacity.inputReservedForFunction("fn")).isEqualTo(oneCanonical);

        currentRecord.markSuccess("current");
        fixture.store.settle(currentRecord);
        assertThat(fixture.capacity.executionReservedForFunction("fn")).isZero();
        assertThat(fixture.capacity.inputReservedForFunction("fn")).isZero();
    }

    @Test
    void synchronousOffloadFailureClosesPhysicalAndAdmissionReservations() {
        Fixture fixture = new Fixture(10, 1_000_000);
        OffloadGateway gateway = mock(OffloadGateway.class);
        when(gateway.enabled()).thenReturn(true);
        when(gateway.shouldOffloadEagerly(any())).thenReturn(true);
        when(gateway.targetUrl(any())).thenReturn("http://remote");
        when(gateway.invokeRemote(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("submit failed"));
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                fixture.store, null, new DispatcherRouter(new LocalDispatcher(), null),
                new Metrics(new SimpleMeterRegistry()), null, fixture.generations);
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                null, new Metrics(new SimpleMeterRegistry()), null, gateway, handler,
                new InvocationResponseMapper());
        var lookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest(new ArrayList<>(java.util.List.of("x")), Map.of()),
                null, null, InvocationKind.SYNC);

        assertThatThrownBy(() -> coordinator.invoke(
                lookup, spec(), 10_000, OffloadContext.none()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("submit failed");
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
        assertThat(fixture.store.getOrNull(lookup.executionRecord().executionId())).isNull();
    }

    @Test
    void administrativelySettledQueueEntryKeepsCanonicalInputUntilTheEntryIsRemoved() {
        Fixture fixture = new Fixture(10, 1_000_000);
        var lookup = fixture.factory.createOrReuseExecution(
                "fn", spec(), new InvocationRequest(new ArrayList<>(java.util.List.of("queued")), Map.of()),
                null, null, InvocationKind.SYNC);
        ExecutionRecord record = lookup.executionRecord();
        var queuedTask = record.prepareForQueue();
        long retained = fixture.capacity.inputReservedGlobally();

        record.markTimeout();
        fixture.store.settle(record);
        assertThat(fixture.store.getOrNull(record.executionId())).isNull();
        assertThat(fixture.capacity.executionReservedGlobally()).isZero();
        assertThat(fixture.capacity.inputReservedGlobally()).isEqualTo(retained);

        queuedTask.releaseQueuedInput();
        assertThat(fixture.capacity.inputReservedGlobally()).isZero();
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("fn", "image", null, Map.of(), null,
                1_000, 1, 10, 1, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static final class Fixture {
        private final ExecutionStore store = new ExecutionStore();
        private final FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        private final InvocationCapacity capacity;
        private final InvocationExecutionFactory factory;

        private Fixture(long executions, long inputBytes) {
            generations.register("fn", 1);
            capacity = new InvocationCapacity(
                    generations, executions, executions, inputBytes, inputBytes, 16);
            factory = new InvocationExecutionFactory(
                    store, new IdempotencyStore(), new Metrics(new SimpleMeterRegistry()),
                    capacity, INPUT_LIMITS);
        }
    }
}
