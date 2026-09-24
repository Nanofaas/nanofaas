package it.unimib.datai.nanofaas.modules.asyncqueue;

import static org.assertj.core.api.Assertions.assertThat;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

class PerFunctionSchedulingStrategyTest {

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }

    @Test
    void id_isPerFunction() {
        assertThat(new PerFunctionSchedulingStrategy().id()).isEqualTo("per-function");
    }

    /**
     * The turn rotation and its bound: a hot function with queued tickets must not hold the turn
     * past the batch while a second function waits. The blocked variant of the same fairness property is
     * {@link #blockedFunctionDoesNotHideReadyWork}.
     */
    @Test
    void turnMovesToNextFunctionAfterMaxBatchPerFunctionDispatches() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");

        index.add(ticket("a0", "a", 0));
        index.add(ticket("a1", "a", 1));
        index.add(ticket("a2", "a", 2));
        index.add(ticket("b0", "b", 3));

        SchedulingTicket first = index.select(now, generation -> true);
        assertThat(first.id()).isEqualTo(new TicketId("a0", 1));
        index.remove(first.id());

        SchedulingTicket second = index.select(now, generation -> true);
        assertThat(second.id()).isEqualTo(new TicketId("a1", 1));
        index.remove(second.id());

        // Batch of two A dispatches consumed: the turn moves to B even though A still
        // has a queued ticket (a2).
        SchedulingTicket third = index.select(now, generation -> true);
        assertThat(third.id()).isEqualTo(new TicketId("b0", 1));
        index.remove(third.id());

        SchedulingTicket fourth = index.select(now, generation -> true);
        assertThat(fourth.id()).isEqualTo(new TicketId("a2", 1));

        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void blockedFunctionDoesNotHideReadyWork() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        index.add(ticket("a1", "blocked", 0));
        index.add(ticket("b1", "ready", 1));

        SchedulingTicket selected = index.select(Instant.parse("2026-09-16T10:00:01Z"),
                generation -> generation.functionName().equals("ready"));

        assertThat(selected.id()).isEqualTo(new TicketId("b1", 1));
        assertThat(index.size()).isEqualTo(2);
        index.remove(selected.id());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void removeIsIdempotentAndClearResetsState() {
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        index.add(ticket("a0", "a", 0));
        TicketId id = new TicketId("a0", 1);

        index.remove(id);
        index.remove(id);
        assertThat(index.size()).isZero();

        index.add(ticket("a0", "a", 0));
        index.clear();
        assertThat(index.size()).isZero();
        assertThat(index.select(Instant.parse("2026-09-16T10:00:01Z"), generation -> true)).isNull();
    }
    @ParameterizedTest
    @ValueSource(strings = {"echo", "other"})
    void engineDelayedHeadDoesNotHideLaterReadyWork(String readyFunction) {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        AtomicReference<Instant> clockNow = new AtomicReference<>(now);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> clockNow.get());
        EngineDispatch dispatch = mock(EngineDispatch.class);
        DispatchOwnership lease = mock(DispatchOwnership.class);
        when(dispatch.tryAcquire(any())).thenReturn(lease);
        PendingWorkStore store = new PendingWorkStore(2);
        var strategy = new PerFunctionSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, generation -> true, generation -> true, clock, () -> 0L);
        SchedulingTicket delayed = new SchedulingTicket(new TicketId("late", 2),
                new FunctionGeneration("echo", 1), 0, now, now.plusSeconds(1), null);
        SchedulingTicket ready = new SchedulingTicket(new TicketId("ready", 1),
                new FunctionGeneration(readyFunction, 1), 1, now, now, null);
        InvocationTask lateTask = new InvocationTask("late", "echo", null, null, null, null,
                now, 2, InvocationKind.ASYNC);
        InvocationTask readyTask = new InvocationTask("ready", readyFunction, null, null, null, null,
                now, 1, InvocationKind.ASYNC);
        assertThat(engine.enqueue(new PendingEntry(delayed, lateTask))).isTrue();
        assertThat(engine.enqueue(new PendingEntry(ready, readyTask))).isTrue();
        engine.tick();
        verify(dispatch).submit(readyTask.withDispatchLease(lease));
        assertThat(store.reservedCount()).isEqualTo(1);
        clockNow.set(now.plusMillis(999));
        engine.tick();
        verify(dispatch, times(1)).submit(any());
        clockNow.set(now.plusSeconds(1));
        engine.tick();
        engine.tick();
        verify(dispatch).submit(lateTask.withDispatchLease(lease));
        verify(dispatch, times(2)).submit(any());
        assertThat(store.reservedCount()).isZero();
    }

}
