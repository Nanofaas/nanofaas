package it.unimib.datai.nanofaas.modules.syncqueue;

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

class SharedQueueSchedulingStrategyTest {

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }

    @Test
    void id_isSharedQueue() {
        assertThat(new SharedQueueSchedulingStrategy().id()).isEqualTo("shared-queue");
    }

    @Test
    void blockedFunctionDoesNotHideReadyWork() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        index.add(ticket("a1", "blocked", 0));
        index.add(ticket("b1", "ready", 1));
        var selected = index.select(Instant.parse("2026-09-16T10:00:01Z"),
                generation -> generation.functionName().equals("ready"));
        assertThat(selected.id()).isEqualTo(new TicketId("b1", 1));
        assertThat(index.size()).isEqualTo(2);
        index.remove(selected.id());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void readyTicketBehindAnyNumberOfBlockedTicketsIsSelectedAtOnce() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");
        for (int i = 0; i < 200; i++) {
            index.add(ticket("blocked-" + i, "blocked", i));
        }
        index.add(ticket("ready", "ready", 200));

        SchedulingTicket found = index.select(now, generation -> generation.functionName().equals("ready"));
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(new TicketId("ready", 1));
        assertThat(index.size()).isEqualTo(201);
    }

    @Test
    void selectionIsGlobalFifoAcrossGenerationsAndEvaluatesEachGenerationOnce() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");
        for (int i = 0; i < 50; i++) {
            index.add(ticket("blocked-" + i, "blocked", i));
        }
        for (int i = 50; i < 100; i++) {
            index.add(ticket("t" + i, i % 2 == 0 ? "even" : "odd", i));
        }
        // The capacity predicate takes a registry lock under the engine gate: once per
        // generation per scan, never once per ticket.
        java.util.Map<FunctionGeneration, Integer> tests = new java.util.HashMap<>();
        List<String> order = new java.util.ArrayList<>();
        for (int i = 50; i < 100; i++) {
            tests.clear();
            SchedulingTicket next = index.select(now, generation -> {
                tests.merge(generation, 1, Integer::sum);
                return !generation.functionName().equals("blocked");
            });
            assertThat(tests.values()).allMatch(count -> count == 1);
            order.add(next.id().executionId());
            index.remove(next.id());
        }
        assertThat(order).containsExactlyElementsOf(java.util.stream.IntStream.range(50, 100)
                .mapToObj(i -> "t" + i).toList());
    }

    /** The scan runs under the engine gate: finding a ticket costs one predicate call per older
     * blocked generation plus one, however many generations are pending. */
    @Test
    void aSuccessfulScanExaminesOnlyTheHeadsOlderThanTheSelectedOne() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");
        for (int i = 0; i < 500; i++) {
            index.add(ticket("t" + i, "fn-" + i, i));
        }
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        assertThat(index.select(now, generation -> calls.incrementAndGet() > 0).id())
                .isEqualTo(new TicketId("t0", 1));
        assertThat(calls).hasValue(1);

        calls.set(0);
        assertThat(index.select(now, generation -> calls.incrementAndGet() > 3).id())
                .isEqualTo(new TicketId("t3", 1));
        assertThat(calls).hasValue(4);
    }

    /** Empty selections under saturation must not push the oldest tickets behind newer ones. */
    @Test
    void engineKeepsFifoOrderAcrossEmptySelectionsOnADeepQueue() {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        var dispatch = mock(EngineDispatch.class);
        var readiness = mock(it.unimib.datai.nanofaas.execution.EngineReadiness.class);
        var lease = mock(DispatchOwnership.class);
        when(dispatch.tryAcquire(any())).thenReturn(lease);
        List<String> submitted = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> submitted.add(((InvocationTask) call.getArgument(0)).executionId()))
                .when(dispatch).submit(any());
        var strategy = new SharedQueueSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(new PendingWorkStore(1024), new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, readiness, generation -> true,
                Clock.fixed(now, java.time.ZoneOffset.UTC), () -> 0L);
        List<String> admitted = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            SchedulingTicket ticket = new SchedulingTicket(new TicketId("e" + i, 1),
                    new FunctionGeneration("echo", 1), i, now, now, null);
            engine.enqueue(new PendingEntry(ticket, new InvocationTask("e" + i, "echo", null, null, null, null,
                    now, 1, InvocationKind.SYNC)));
            admitted.add("e" + i);
        }
        when(readiness.runnable(any())).thenReturn(false);
        for (int i = 0; i < 3; i++) {
            engine.tick(); // nothing runnable: each empty selection used to rotate 64 tickets
        }
        when(readiness.runnable(any())).thenReturn(true);
        for (int i = 0; i < 100; i++) {
            engine.tick();
        }
        assertThat(submitted).containsExactlyElementsOf(admitted);
    }

    /** One pass reaches a runnable function however many blocked tickets are ahead of it,
     * instead of parking once per 64-ticket window. */
    @Test
    void engineDispatchesRunnableWorkBehindADeepBlockedBacklogInOnePass() {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        var dispatch = mock(EngineDispatch.class);
        var readiness = mock(it.unimib.datai.nanofaas.execution.EngineReadiness.class);
        when(dispatch.tryAcquire(any())).thenReturn(mock(DispatchOwnership.class));
        FunctionGeneration blocked = new FunctionGeneration("blocked", 1);
        when(readiness.runnable(any())).thenAnswer(call -> !blocked.equals(call.getArgument(0)));
        var strategy = new SharedQueueSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(new PendingWorkStore(1024), new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, readiness, generation -> true,
                Clock.fixed(now, java.time.ZoneOffset.UTC), () -> 0L);
        for (int i = 0; i < 200; i++) {
            engine.enqueue(new PendingEntry(new SchedulingTicket(new TicketId("b" + i, 1), blocked, i, now, now, null),
                    new InvocationTask("b" + i, "blocked", null, null, null, null, now, 1, InvocationKind.SYNC)));
        }
        InvocationTask ready = new InvocationTask("ready", "ready", null, null, null, null, now, 1, InvocationKind.SYNC);
        engine.enqueue(new PendingEntry(new SchedulingTicket(new TicketId("ready", 1),
                new FunctionGeneration("ready", 1), 200, now, now, null), ready));

        engine.tick();

        verify(dispatch).submit(any());
        assertThat(engine.reservedCount("ready")).isZero();
    }

    @Test
    void removeIsIdempotentAndClearResetsState() {
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
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
        var strategy = new SharedQueueSchedulingStrategy();
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
