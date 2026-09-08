package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for finding R5 of the 2026-09-08 pre-soak review.
 *
 * <p>When the sync gateway is disabled, {@code ReactiveInvocationCoordinator.admitLocally}
 * falls through to direct dispatch, and that direct path never acquires a concurrency
 * slot. Completion nevertheless releases a slot by function name through the sync
 * enqueuer ({@link SyncQueueInvocationEnqueuer#releaseDispatchSlot}), whose
 * {@code enabled()} is deliberately {@code false} — the per-record "released attempts"
 * set only prevents a duplicate release by the same record, it cannot prove the record
 * ever acquired a slot. A direct completion can therefore release a slot owned by an
 * older queued dispatch that is still draining.
 *
 * <p>Correct behavior (plan task P06, invariant I4): every attempt releases exactly the
 * resources it acquired. A direct attempt that never acquired a slot must not decrement
 * another dispatch's slot by function name.
 *
 * <p>This test asserts the desired behavior, so it is RED on the current baseline,
 * where completing the direct attempt drops the old dispatch's in-flight count from 1
 * to 0.
 */
class R5DirectCompletionUnownedSlotRegressionTest {

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    @Test
    void directCompletionDoesNotReleaseASlotItNeverAcquired() {
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("fn", 1);
        // An older queued dispatch is still running and holds the function's only slot.
        assertThat(capacity.tryAcquireSlot("fn")).isTrue();
        assertThat(capacity.inFlight("fn")).isEqualTo(1);

        // The sync enqueuer reports enabled() == false, so a new arrival is admitted down
        // the direct path rather than through the queue, and never acquires a slot.
        SyncQueueInvocationEnqueuer enqueuer = new SyncQueueInvocationEnqueuer(capacity);
        ExecutionStore store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, new DispatcherRouter(new LocalDispatcher(), null), metrics);

        // A direct admission that never acquired a slot completes through the same handler.
        String executionId = "direct-1";
        InvocationTask task = new InvocationTask(executionId, "fn", spec("fn"),
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
        ExecutionRecord record = new ExecutionRecord(executionId, task);
        store.put(record);
        handler.completeExecution(executionId, DispatchResult.warm(InvocationResult.success("ok")));

        // The old dispatch is still running: its slot must not have been released by a
        // direct completion that never acquired it. The baseline reports 0 and fails here.
        assertThat(capacity.inFlight("fn"))
                .as("a direct completion must not release a slot held by an older queued dispatch")
                .isEqualTo(1);
    }
}
