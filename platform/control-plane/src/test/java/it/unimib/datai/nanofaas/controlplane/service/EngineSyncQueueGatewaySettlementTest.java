package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionController;
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionResult;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineSyncQueueGatewaySettlementTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private final SyncQueueAdmissionController controller = mock(SyncQueueAdmissionController.class);
    private final WaitEstimator estimator = mock(WaitEstimator.class);

    {
        when(controller.evaluate(any(), anyInt(), any())).thenReturn(SyncQueueAdmissionResult.accepted(0));
    }

    @Test
    void dispatchDuringAdmissionSettlesDepthAndRecordsWaitSample() {
        AtomicInteger admissions = new AtomicInteger();
        PendingWorkStore store = new PendingWorkStore(8);
        SyncQueueConfigSource config = mock(SyncQueueConfigSource.class);
        when(config.syncQueueMaxQueueWait()).thenReturn(Duration.ofSeconds(30));
        DispatchCapacity capacity = mock(DispatchCapacity.class);
        when(capacity.activeGeneration("echo")).thenReturn(new FunctionGeneration("echo", 1));
        SchedulerEngine engine = mock(SchedulerEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        EngineSyncQueueGateway gateway = new EngineSyncQueueGateway(config, controller, estimator,
                provider, store, capacity, () -> 0L,
                EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE,
                function -> admissions.incrementAndGet(), function -> { },
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            PendingEntry entry = invocation.getArgument(0);
            assertThat(store.offer(entry)).isTrue();
            store.claim(entry.ticket().id());
            store.commit(entry.ticket().id());
            gateway.recordDispatched(entry.task().functionName(), NOW);
            store.finishSubmit(entry.ticket().id());
            return true;
        }).when(engine).enqueue(any());
        InvocationTask task = new InvocationTask("e1", "echo", null, null, null, null,
                NOW, 1, InvocationKind.SYNC);

        gateway.enqueueOrThrow(task);

        assertAll(
                () -> assertThat(store.reservedCount()).isZero(),
                () -> assertThat(admissions).hasValue(1));
        verify(estimator).recordDispatch("echo", NOW);

        gateway.functionRemoved("echo");
        verify(estimator).removeFunctionState("echo");
    }

    @Test
    void timedRetryPreservesAdmissionTimeAndQueueDeadline() {
        SyncQueueConfigSource config = mock(SyncQueueConfigSource.class);
        when(config.syncQueueMaxQueueWait()).thenReturn(Duration.ofMillis(100));
        DispatchCapacity capacity = mock(DispatchCapacity.class);
        when(capacity.activeGeneration("echo")).thenReturn(new FunctionGeneration("echo", 1));
        SchedulerEngine engine = mock(SchedulerEngine.class);
        when(engine.enqueue(any())).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        EngineSyncQueueGateway gateway = new EngineSyncQueueGateway(config, controller, estimator,
                provider, new PendingWorkStore(8), capacity, () -> 0L,
                EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE, function -> {}, function -> {},
                Clock.fixed(NOW, ZoneOffset.UTC));
        InvocationTask task = new InvocationTask("e2", "echo", null, null, null, null,
                NOW, 2, InvocationKind.SYNC);

        assertThat(gateway.enqueue(task, NOW.plusSeconds(1))).isTrue();

        var admitted = org.mockito.ArgumentCaptor.forClass(PendingEntry.class);
        verify(engine).enqueue(admitted.capture());
        assertThat(admitted.getValue().ticket().notBefore()).isEqualTo(NOW.plusSeconds(1));
        assertThat(admitted.getValue().ticket().enqueuedAt()).isEqualTo(NOW);
        assertThat(admitted.getValue().ticket().queueDeadline()).isEqualTo(NOW.plusMillis(100));
    }

    @Test
    void rejectedAdmissionLeavesNoReservation() {
        SyncQueueConfigSource config = mock(SyncQueueConfigSource.class);
        when(config.syncQueueMaxQueueWait()).thenReturn(Duration.ofSeconds(30));
        DispatchCapacity capacity = mock(DispatchCapacity.class);
        when(capacity.activeGeneration("echo")).thenReturn(new FunctionGeneration("echo", 1));
        SchedulerEngine engine = mock(SchedulerEngine.class); // enqueue returns false
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        PendingWorkStore store = new PendingWorkStore(8);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        EngineSyncQueueGateway gateway = new EngineSyncQueueGateway(config, controller, estimator,
                provider, store, capacity, () -> 0L,
                EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE,
                function -> admitted.incrementAndGet(), function -> rejected.incrementAndGet(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        InvocationTask task = new InvocationTask("e2", "echo", null, null, null, null,
                NOW, 1, InvocationKind.SYNC);

        assertThatThrownBy(() -> gateway.enqueueOrThrow(task)).isInstanceOf(SyncQueueRejectedException.class);

        assertThat(admitted).hasValue(0);
        assertThat(rejected).hasValue(1);
        assertThat(store.reservedCount()).isZero();
    }
    @Test
    void retryUsesCapturedGenerationRatherThanReplacement() {
        SyncQueueConfigSource config = mock(SyncQueueConfigSource.class);
        when(config.syncQueueMaxQueueWait()).thenReturn(Duration.ofSeconds(30));
        DispatchCapacity capacity = mock(DispatchCapacity.class);
        FunctionGeneration old = new FunctionGeneration("echo", 1);
        when(capacity.activeGeneration("echo")).thenReturn(new FunctionGeneration("echo", 2));
        SchedulerEngine engine = mock(SchedulerEngine.class);
        when(engine.enqueue(any())).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        EngineSyncQueueGateway gateway = new EngineSyncQueueGateway(config, controller, estimator,
                provider, new PendingWorkStore(8), capacity, () -> 0L,
                EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE, function -> {}, function -> {},
                Clock.fixed(NOW, ZoneOffset.UTC));
        InvocationTask task = new InvocationTask("e2", "echo", null, null, null, null,
                NOW, 2, InvocationKind.SYNC);
        gateway.enqueue(task, NOW.plusSeconds(1), old);
        var admitted = org.mockito.ArgumentCaptor.forClass(PendingEntry.class);
        verify(engine).enqueue(admitted.capture());
        assertThat(admitted.getValue().ticket().generation()).isEqualTo(old);
    }

}
