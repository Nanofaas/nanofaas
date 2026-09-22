package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineSyncQueueGatewaySettlementTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void dispatchDuringAdmissionSettlesDepthAndRecordsWaitSample() {
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger samples = new AtomicInteger();
        AtomicBoolean settled = new AtomicBoolean();
        PendingWorkStore store = new PendingWorkStore(8);
        SyncQueueConfigSource config = mock(SyncQueueConfigSource.class);
        when(config.syncQueueMaxQueueWait()).thenReturn(Duration.ofSeconds(30));
        DispatchCapacity capacity = mock(DispatchCapacity.class);
        when(capacity.activeGeneration("echo")).thenReturn(new FunctionGeneration("echo", 1));
        SchedulerEngine engine = mock(SchedulerEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        EngineSyncQueueGateway gateway = new EngineSyncQueueGateway(config,
                (function, pending, now) -> null,
                (function, now) -> samples.incrementAndGet(), function -> { },
                provider, store, capacity, () -> 0L,
                EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE,
                function -> depth.incrementAndGet(), function -> { },
                function -> {
                    assertThat(depth.get()).isPositive();
                    depth.decrementAndGet();
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            PendingEntry entry = invocation.getArgument(0);
            assertThat(store.offer(entry)).isTrue();
            boolean syncOrigin = gateway.settleIfSyncOrigin(entry.task().functionName(), entry.ticket().id());
            settled.set(syncOrigin);
            if (syncOrigin) {
                gateway.recordDispatched(entry.task().functionName(), NOW);
            }
            store.remove(entry.ticket().id());
            return true;
        }).when(engine).enqueue(any());
        InvocationTask task = new InvocationTask("e1", "echo", null, null, null, null,
                NOW, 1, InvocationKind.SYNC);

        gateway.enqueueOrThrow(task);

        assertAll(
                () -> assertThat(settled).isTrue(),
                () -> assertThat(depth).hasValue(0),
                () -> assertThat(samples).hasValue(1),
                () -> assertThat(gateway.settleIfSyncOrigin("echo", new TicketId("e1", 1))).isFalse());
    }

    @Test
    void rejectedAdmissionLeavesNoSyncOriginToSettle() {
        SyncQueueConfigSource config = mock(SyncQueueConfigSource.class);
        when(config.syncQueueMaxQueueWait()).thenReturn(Duration.ofSeconds(30));
        DispatchCapacity capacity = mock(DispatchCapacity.class);
        when(capacity.activeGeneration("echo")).thenReturn(new FunctionGeneration("echo", 1));
        SchedulerEngine engine = mock(SchedulerEngine.class); // enqueue returns false
        @SuppressWarnings("unchecked")
        ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        EngineSyncQueueGateway gateway = new EngineSyncQueueGateway(config,
                (function, pending, now) -> null,
                (function, now) -> { }, function -> { },
                provider, new PendingWorkStore(8), capacity, () -> 0L,
                EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE,
                function -> admitted.incrementAndGet(), function -> rejected.incrementAndGet(),
                function -> { }, Clock.fixed(NOW, ZoneOffset.UTC));
        InvocationTask task = new InvocationTask("e2", "echo", null, null, null, null,
                NOW, 1, InvocationKind.SYNC);

        assertThatThrownBy(() -> gateway.enqueueOrThrow(task)).isInstanceOf(SyncQueueRejectedException.class);

        assertThat(gateway.settleIfSyncOrigin("echo", new TicketId("e2", 1))).isFalse();
        assertThat(admitted).hasValue(0);
        assertThat(rejected).hasValue(1);
    }
}
