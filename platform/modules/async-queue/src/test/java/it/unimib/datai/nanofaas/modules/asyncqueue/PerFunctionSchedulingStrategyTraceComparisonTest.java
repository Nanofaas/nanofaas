package it.unimib.datai.nanofaas.modules.asyncqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Compares the selection order of the real {@link Scheduler} against
 * {@link PerFunctionSchedulingStrategy} on a corpus that exercises every event kind the
 * plan mandates: publish, a function going blocked then unblocked mid-stream, dispatches
 * interleaved across three functions (so turn/batch accounting is live), and a standalone
 * removal that is not a dispatch.
 *
 * <p>Function {@code a} has concurrency 1 with three queued tickets: dispatching the first
 * exhausts its only slot, so the second attempt in the same turn blocks (mirrors
 * {@code Scheduler.processFunction} hitting {@code tryAcquireLease} failure at
 * {@code Scheduler.java:184-187} and ending the visit without a re-signal because
 * {@code canDispatch()} is false at {@code Scheduler.java:245}). Releasing that lease
 * unblocks {@code a} and lets it resume — twice, since concurrency 1 means every dispatch
 * blocks the next one. Functions {@code b} (three tickets) and {@code c} (two tickets) have
 * ample concurrency and dispatch normally, including one full batch-of-2 turn each, so
 * turn/batch bookkeeping is exercised on the live path too. Function {@code d} is enqueued
 * and then removed before the scheduler ever starts — a removal with no dispatch at all.
 */
class PerFunctionSchedulingStrategyTraceComparisonTest {

    @Test
    void oldSchedulerAndNewIndexAgreeOnFullCorpusTrace() {
        List<String> oldOrder = new CopyOnWriteArrayList<>();
        Map<String, InvocationTask> dispatchedTasks = new LinkedHashMap<>();

        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        FunctionSpec specA = functionSpec("a", 1, 100);
        FunctionSpec specB = functionSpec("b", 10, 100);
        FunctionSpec specC = functionSpec("c", 10, 100);
        FunctionSpec specD = functionSpec("d", 10, 100);
        queueManager.getOrCreate(specA);
        queueManager.getOrCreate(specB);
        queueManager.getOrCreate(specC);
        queueManager.getOrCreate(specD);

        InvocationDispatch invocationService = mock(InvocationDispatch.class);
        doAnswer(invocation -> {
            InvocationTask t = invocation.getArgument(0);
            synchronized (dispatchedTasks) {
                dispatchedTasks.put(t.executionId(), t);
            }
            oldOrder.add(t.executionId());
            return null;
        }).when(invocationService).dispatch(org.mockito.ArgumentMatchers.any());

        Scheduler scheduler = new Scheduler(queueManager, invocationService, mock(QueueLifecycle.class));
        scheduler.init();

        // Publish corpus, in this exact order.
        queueManager.enqueue(task("a0", specA));
        queueManager.enqueue(task("b0", specB));
        queueManager.enqueue(task("c0", specC));
        queueManager.enqueue(task("a1", specA));
        queueManager.enqueue(task("b1", specB));
        queueManager.enqueue(task("c1", specC));
        queueManager.enqueue(task("a2", specA));
        queueManager.enqueue(task("b2", specB));
        queueManager.enqueue(task("d0", specD));

        // Standalone removal: d0 is drained before the scheduler ever runs - a removal that
        // is not, and never becomes, a dispatch.
        List<InvocationTask> drained = queueManager.remove("d");
        assertThat(drained).extracting(InvocationTask::executionId).containsExactly("d0");

        scheduler.start();
        try {
            // Phase 1: everything dispatchable before "a" blocks a second time.
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(oldOrder).hasSize(6));
            // Stable for a beat: "a" is blocked (its one slot is held by a0) and nothing else
            // is left ready, so no further dispatch should happen without an unblock.
            Awaitility.await().during(Duration.ofMillis(200)).atMost(Duration.ofMillis(600))
                    .untilAsserted(() -> assertThat(oldOrder).hasSize(6));

            // Unblock #1: release a0's lease.
            dispatchedTasks.get("a0").dispatchLease().release();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(oldOrder).hasSize(7));
            Awaitility.await().during(Duration.ofMillis(200)).atMost(Duration.ofMillis(600))
                    .untilAsserted(() -> assertThat(oldOrder).hasSize(7));

            // Unblock #2: release a1's lease.
            dispatchedTasks.get("a1").dispatchLease().release();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(oldOrder).hasSize(8));
        } finally {
            scheduler.stop();
        }

        assertThat(oldOrder).containsExactly("a0", "b0", "b1", "c0", "c1", "b2", "a1", "a2");
        assertThat(oldOrder).doesNotContain("d0");

        // ---- NEW: PerFunctionSchedulingStrategy index, driven by the identical corpus and
        // the identical concurrency-1-for-"a" capacity model. ----
        SchedulingIndex index = new PerFunctionSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");

        index.add(ticket("a0", "a", 0));
        index.add(ticket("b0", "b", 1));
        index.add(ticket("c0", "c", 2));
        index.add(ticket("a1", "a", 3));
        index.add(ticket("b1", "b", 4));
        index.add(ticket("c1", "c", 5));
        index.add(ticket("a2", "a", 6));
        index.add(ticket("b2", "b", 7));
        index.add(ticket("d0", "d", 8));

        // Same standalone removal, upfront, mirroring queueManager.remove("d") above.
        index.remove(new TicketId("d0", 1));
        assertThat(index.size()).isEqualTo(8);

        // "a" has concurrency 1 in the old run: at most one of its tickets is ever in flight,
        // exactly like the real DispatchOwnership lease. aInFlight models that lease count -
        // incremented the instant an "a" ticket is removed (dispatched), decremented by an
        // explicit "release" between phases, mirroring dispatchLease().release() above.
        int[] aInFlight = {0};
        java.util.function.Predicate<FunctionGeneration> runnable =
                g -> !g.functionName().equals("a") || aInFlight[0] < 1;
        List<String> newOrder = new java.util.ArrayList<>();

        drainWhileRunnable(index, now, runnable, newOrder, aInFlight);
        // "a" is now blocked (its only lease is held by a0), matching the old run's stable
        // 6-dispatch plateau.
        assertThat(index.select(now, runnable)).isNull();

        aInFlight[0]--; // unblock #1: release a0's lease
        drainWhileRunnable(index, now, runnable, newOrder, aInFlight);
        assertThat(index.select(now, runnable)).isNull();

        aInFlight[0]--; // unblock #2: release a1's lease
        drainWhileRunnable(index, now, runnable, newOrder, aInFlight);

        assertThat(newOrder).containsExactlyElementsOf(oldOrder);
    }

    /**
     * Selects and removes until nothing more is runnable, incrementing {@code aInFlight}
     * whenever an "a" ticket is dispatched - the moment its one lease slot is taken.
     */
    private static void drainWhileRunnable(SchedulingIndex index, Instant now,
            java.util.function.Predicate<FunctionGeneration> runnable, List<String> order,
            int[] aInFlight) {
        SchedulingTicket ticket;
        while ((ticket = index.select(now, runnable)) != null) {
            order.add(ticket.id().executionId());
            index.remove(ticket.id());
            if (ticket.generation().functionName().equals("a")) {
                aInFlight[0]++;
            }
        }
    }

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }

    private FunctionSpec functionSpec(String functionName, int concurrency, int queueSize) {
        return new FunctionSpec(functionName, "image", null, Map.of(), null, 1000, concurrency,
                queueSize, 3, null, ExecutionMode.LOCAL, null, null, null);
    }

    private InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload-" + executionId, Map.of()), null, null,
                Instant.now(), 1, InvocationKind.SYNC);
    }
}
