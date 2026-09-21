package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Compares the selection order of the real {@code SyncQueueService} scan/rotation methods
 * (exactly what the retired {@code SyncScheduler}'s tick drove, before Task 13b deleted it) against
 * {@link SharedQueueSchedulingStrategy} on a corpus covering every mandated event kind:
 * publish, a function going blocked then unblocked mid-stream, dispatches interleaved
 * across three functions, and a standalone removal that is not a dispatch.
 *
 * <p>Function {@code x} is blocked (its capacity predicate returns false) while {@code y}
 * and {@code z} are always dispatchable and interleaved with it; {@code w} is enqueued and
 * then removed via {@code removeReady} before ever being scanned as dispatchable - a
 * removal distinct from a dispatch. Once {@code y}/{@code z} drain and only {@code x}'s two
 * tickets remain, {@code x} unblocks mid-stream and both dispatch.
 */
class SharedQueueSchedulingStrategyTraceComparisonTest {

    @Test
    void oldServiceAndNewIndexAgreeOnFullCorpusTrace() {
        // ---- OLD: real SyncQueueService, driven the way the retired loop's tick did ----
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 200, Duration.ofSeconds(30), Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 3);
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        SyncQueueService service = new SyncQueueService(props, new ExecutionStore(),
                new WaitEstimator(Duration.ofSeconds(30), 3), new SyncQueueMetrics(new SimpleMeterRegistry()),
                Clock.systemUTC(), SyncQueueConfigSource.fixed(props.runtimeDefaults()), capacity, null);
        capacity.register("x", 10);
        capacity.register("y", 10);
        capacity.register("z", 10);
        capacity.register("w", 10);

        // Publish, in this exact order.
        service.enqueueOrThrow(task("x0", "x"));
        service.enqueueOrThrow(task("y0", "y"));
        service.enqueueOrThrow(task("x1", "x"));
        service.enqueueOrThrow(task("z0", "z"));
        service.enqueueOrThrow(task("y1", "y"));
        service.enqueueOrThrow(task("z1", "z"));
        service.enqueueOrThrow(task("w0", "w"));

        // Standalone removal: w0 is cancelled before it is ever scanned as dispatchable - a
        // removal that is not, and never becomes, a dispatch.
        SyncQueueItem w0 = service.findReadyMatching(Instant.now(), t -> t.functionName().equals("w"));
        assertThat(w0).isNotNull();
        assertThat(service.removeReady(w0, Instant.now())).isTrue();

        Set<String> blocked = new java.util.HashSet<>(Set.of("x"));
        List<String> oldOrder = drainOld(service, blocked);
        assertThat(oldOrder).containsExactly("y0", "z0", "y1", "z1");
        // Nothing left but blocked "x" tickets: one more scan finds nothing, and rotating the
        // (fully blocked) window is a no-op for dispatchability.
        assertThat(service.findReadyMatching(Instant.now(), t -> !blocked.contains(t.functionName())))
                .isNull();
        service.rotateReadyScanWindow(Instant.now());

        // Unblock "x" mid-stream.
        blocked.remove("x");
        oldOrder.addAll(drainOld(service, blocked));

        assertThat(oldOrder).containsExactly("y0", "z0", "y1", "z1", "x0", "x1");

        // ---- NEW: SharedQueueSchedulingStrategy index, driven by the identical corpus ----
        SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
        Instant now = Instant.parse("2026-09-16T10:00:01Z");
        index.add(ticket("x0", "x", 0));
        index.add(ticket("y0", "y", 1));
        index.add(ticket("x1", "x", 2));
        index.add(ticket("z0", "z", 3));
        index.add(ticket("y1", "y", 4));
        index.add(ticket("z1", "z", 5));
        index.add(ticket("w0", "w", 6));

        // Same standalone removal, mirroring service.removeReady(w0, ...) above.
        index.remove(new TicketId("w0", 1));
        assertThat(index.size()).isEqualTo(6);

        Set<String> newBlocked = new java.util.HashSet<>(Set.of("x"));
        Predicate<FunctionGeneration> runnable = g -> !newBlocked.contains(g.functionName());
        List<String> newOrder = drainNew(index, now, runnable);
        assertThat(index.select(now, runnable)).isNull();
        index.defer(new TicketId("x0", 1)); // mirrors service.rotateReadyScanWindow(now) above

        newBlocked.remove("x");
        newOrder.addAll(drainNew(index, now, runnable));

        assertThat(newOrder).containsExactlyElementsOf(oldOrder);
    }

    /** Drains every currently-dispatchable item, exactly as the retired loop's tick would. */
    private static List<String> drainOld(SyncQueueService service, Set<String> blocked) {
        List<String> order = new ArrayList<>();
        SyncQueueItem item;
        while ((item = service.findReadyMatching(Instant.now(), t -> !blocked.contains(t.functionName()))) != null) {
            Instant now = Instant.now();
            assertThat(service.removeReadyForDispatch(item, now)).isTrue();
            order.add(item.task().executionId());
            service.completeDispatchReservation(item);
        }
        return order;
    }

    private static List<String> drainNew(SchedulingIndex index, Instant now,
            Predicate<FunctionGeneration> runnable) {
        List<String> order = new ArrayList<>();
        SchedulingTicket ticket;
        while ((ticket = index.select(now, runnable)) != null) {
            order.add(ticket.id().executionId());
            index.remove(ticket.id());
        }
        return order;
    }

    private static SchedulingTicket ticket(String id, String function, long sequence) {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
                sequence, now, now, now.plusSeconds(60));
    }

    private static FunctionSpec functionSpec(String functionName) {
        return new FunctionSpec(functionName, "image", null, Map.of(), null, 1000, 10,
                200, 3, null, ExecutionMode.LOCAL, null, null, null);
    }

    private InvocationTask task(String executionId, String functionName) {
        FunctionSpec spec = functionSpec(functionName);
        return new InvocationTask(executionId, functionName, spec,
                new InvocationRequest("payload-" + executionId, Map.of()), null, null,
                Instant.now(), 1, InvocationKind.SYNC);
    }
}
