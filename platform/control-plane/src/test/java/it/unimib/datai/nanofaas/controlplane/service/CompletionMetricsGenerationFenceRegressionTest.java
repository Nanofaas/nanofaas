package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the P09 review finding: {@link ExecutionCompletionHandler} recorded
 * core completion counters/timers by function name alone, so a completion admitted under an
 * old generation could still attribute to a same-named function's new generation after a
 * remove/re-register raced it (invariant I7, ADR 0001 &sect;8.2).
 *
 * <p>RED on the baseline before this fix: the assertion below failed because
 * {@code metrics.success}/{@code error} always resolved by name, with no generation check.
 */
class CompletionMetricsGenerationFenceRegressionTest {

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10_000, 1, 100, 0, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
    }

    @Test
    void aLateCompletionAfterRemoveAndReRegisterDoesNotContaminateTheNewGenerationsMeters() {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Metrics metrics = new Metrics(meterRegistry, capacityRegistry);
        ExecutionStore store = new ExecutionStore();
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                new DispatcherRouter(new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return backend;
                    }
                }, null), metrics, null, capacityRegistry);

        FunctionSpec spec = spec("fn");
        capacityRegistry.register("fn", 1);
        metrics.registerFunction("fn");

        InvocationTask task = task("exec-old", spec);
        ExecutionRecord executionRecord = new ExecutionRecord("exec-old", task);
        store.put(executionRecord);
        handler.dispatchDirect(task);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.RUNNING);

        // Remove and re-register the same name while the old attempt's dispatch is still
        // pending: same public tag, a new internal generation.
        capacityRegistry.remove("fn");
        metrics.removeFunction("fn");
        capacityRegistry.register("fn", 1);
        metrics.registerFunction("fn");

        // The old attempt's dispatch finally completes, late, after the swap.
        backend.complete(DispatchResult.warm(InvocationResult.success("late")));

        // The execution itself still concludes normally...
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        // ...but the stale completion must not attribute to the new generation's counters.
        assertThat(meterRegistry.get("function_success_total").tag("function", "fn")
                        .counter().count())
                .as("a completion admitted under the old generation must not increment "
                        + "the new generation's success counter")
                .isZero();
        assertThat(meterRegistry.get("function_error_total").tag("function", "fn")
                        .counter().count())
                .as("the stale completion must not count as an error against the new generation either")
                .isZero();
    }
}
