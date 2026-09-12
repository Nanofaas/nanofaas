package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncSchedulerTest {
    @Test
    void dispatchesWhenSlotAvailable() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "fn", true);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store);

        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> dispatchCount.incrementAndGet(), org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
    }

    @Test
    void leavesItemQueuedWhenSlotAcquisitionFailsAfterSelection() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "fn", false, true);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store);

        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> dispatchCount.incrementAndGet(), org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();

        assertEquals(0, dispatchCount.get());
        assertEquals(1, queue.queuedItems());

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
        assertEquals(0, queue.queuedItems());
    }

    @Test
    void failedSlotAcquisitionDoesNotCreateTemporaryQueueCapacity() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("fn")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "fn", false);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store, 1);

        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        InvocationTask refill = new InvocationTask("e2", "fn", spec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        store.put(new ExecutionRecord("e2", refill));
        queue.enqueueOrThrow(task);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> dispatchCount.incrementAndGet(), org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();

        assertEquals(0, dispatchCount.get());
        assertEquals(1, queue.queuedItems());
        assertThrows(SyncQueueRejectedException.class, () -> queue.enqueueOrThrow(refill));
    }

    @Test
    void blockedScanRotationDoesNotCreateTemporaryQueueCapacity() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec spec = new FunctionSpec("blocked", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("blocked")).thenReturn(false);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store, 1);

        InvocationTask task = new InvocationTask("e1", "blocked", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        InvocationTask refill = new InvocationTask("e2", "blocked", spec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", task));
        store.put(new ExecutionRecord("e2", refill));
        queue.enqueueOrThrow(task);

        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> {
        }, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();

        assertEquals(1, queue.queuedItems());
        assertThrows(SyncQueueRejectedException.class, () -> queue.enqueueOrThrow(refill));
    }

    @Test
    void selectsLaterTaskWhenHeadFunctionHasNoAvailableSlot() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec blockedSpec = new FunctionSpec("blocked", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        FunctionSpec readySpec = new FunctionSpec("ready", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("blocked")).thenReturn(false);
        when(enqueuer.hasAvailableSlot("ready")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "ready", true);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store);

        InvocationTask blocked = new InvocationTask("e1", "blocked", blockedSpec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        InvocationTask ready = new InvocationTask("e2", "ready", readySpec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", blocked));
        store.put(new ExecutionRecord("e2", ready));
        queue.enqueueOrThrow(blocked);
        queue.enqueueOrThrow(ready);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> {
            assertEquals("ready", t.functionName());
            dispatchCount.incrementAndGet();
        }, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
        assertEquals(1, queue.queuedItems());
        verify(enqueuer).hasAvailableSlot("blocked");
        verify(enqueuer).hasAvailableSlot("ready");
        verify(enqueuer, never()).tryAcquireLease(argThat(t -> t != null && t.functionName().equals("blocked")));
    }

    @Test
    void rotatesCandidateWhenFinalSlotAcquisitionFails() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec hotSpec = new FunctionSpec("hot", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        FunctionSpec readySpec = new FunctionSpec("ready", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("hot")).thenReturn(true);
        when(enqueuer.hasAvailableSlot("ready")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "hot", false);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "ready", true);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store);

        InvocationTask hot = new InvocationTask("e1", "hot", hotSpec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        InvocationTask ready = new InvocationTask("e2", "ready", readySpec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord("e1", hot));
        store.put(new ExecutionRecord("e2", ready));
        queue.enqueueOrThrow(hot);
        queue.enqueueOrThrow(ready);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> {
            assertEquals("ready", t.functionName());
            dispatchCount.incrementAndGet();
        }, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();
        assertEquals(0, dispatchCount.get());

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
        assertEquals(1, queue.queuedItems());
        verify(enqueuer).tryAcquireLease(argThat(t -> t != null && t.functionName().equals("hot")));
        verify(enqueuer).tryAcquireLease(argThat(t -> t != null && t.functionName().equals("ready")));
    }

    @Test
    void eventuallySelectsReadyTaskBeyondFirstScanWindow() {
        QueuedDispatchCapacity enqueuer = it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.enqueuer();
        FunctionSpec blockedSpec = new FunctionSpec("blocked", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        FunctionSpec readySpec = new FunctionSpec("ready", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        when(enqueuer.hasAvailableSlot("blocked")).thenReturn(false);
        when(enqueuer.hasAvailableSlot("ready")).thenReturn(true);
        it.unimib.datai.nanofaas.modules.syncqueue.SchedulerLeaseTestSupport.allow(enqueuer, "ready", true);

        ExecutionStore store = new ExecutionStore();
        SyncQueueService queue = queue(store);
        for (int i = 0; i < SyncQueueService.POLL_READY_MATCHING_SCAN_LIMIT; i++) {
            InvocationTask blocked = new InvocationTask("blocked-" + i, "blocked", blockedSpec, new InvocationRequest("blocked", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
            store.put(new ExecutionRecord(blocked.executionId(), blocked));
            queue.enqueueOrThrow(blocked);
        }
        InvocationTask ready = new InvocationTask("ready", "ready", readySpec, new InvocationRequest("ready", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
        store.put(new ExecutionRecord(ready.executionId(), ready));
        queue.enqueueOrThrow(ready);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(enqueuer, queue, t -> {
            assertEquals("ready", t.functionName());
            dispatchCount.incrementAndGet();
        }, org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));

        scheduler.tickOnce();
        assertEquals(0, dispatchCount.get());

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
        assertEquals(SyncQueueService.POLL_READY_MATCHING_SCAN_LIMIT, queue.queuedItems());
    }

    private static SyncQueueService queue(ExecutionStore store) {
        return queue(store, 100);
    }

    private static SyncQueueService queue(ExecutionStore store, int maxDepth) {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, maxDepth, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(props.runtimeDefaults());
        return new SyncQueueService(props, store, metrics, configSource, new FunctionCapacityRegistry(), null);
    }
}
