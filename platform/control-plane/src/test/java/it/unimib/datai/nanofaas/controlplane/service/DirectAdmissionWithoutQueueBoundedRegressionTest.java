package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the "Direct core admission" risk of the 2026-09-08 pre-soak
 * review (additional memory and operational risks table).
 *
 * <p>With no queue module installed, the initial attempt bypasses slot acquisition
 * entirely: {@link ReactiveInvocationCoordinator#invoke} falls through to direct
 * dispatch and never consults the function's configured concurrency. The diagnostic
 * harness observes 100 simultaneously dispatched live records for a function whose
 * {@code concurrency} is 1.
 *
 * <p>Correct behavior (plan tasks P06/P07, invariant I5): new work has a finite
 * admission bound even without a queue/governor module, so at most the configured
 * concurrency worth of executions is dispatched live.
 *
 * <p>This test asserts the desired behavior, so it is RED on the current baseline,
 * where all 100 attempts are dispatched and held live.
 */
class DirectAdmissionWithoutQueueBoundedRegressionTest {

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationExecutionFactory.ExecutionLookup lookup(
            InvocationExecutionFactory factory) {
        return factory.createOrReuseExecution("fn", spec("fn"),
                new InvocationRequest("payload", Map.of()), null, null, InvocationKind.SYNC);
    }

    @Test
    void noQueueModuleStillBoundsLiveDispatchesToTheConfiguredConcurrency() {
        ExecutionStore store = new ExecutionStore();
        IdempotencyStore keys = new IdempotencyStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        CompletableFuture<DispatchResult> backend = new CompletableFuture<>();
        TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(store, keys, metrics, "fn");
        InvocationExecutionFactory factory = runtime.factory();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                new DispatcherRouter(new LocalDispatcher() {
                    @Override
                    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
                        return backend;
                    }
                }, null), metrics);
        ReactiveInvocationCoordinator coordinator =
                new ReactiveInvocationCoordinator(null, metrics, null, null, handler,
                        new InvocationResponseMapper(), runtime.waiters());

        List<CompletableFuture<SyncInvocation>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                attempts.add(coordinator.invoke(lookup(factory), spec("fn"), 10_000).toFuture());
            }
            int liveDispatched = store.inFlightCount();
            assertThat(liveDispatched)
                    .as("direct core admission without a queue module must still respect the "
                            + "function's configured concurrency of 1")
                    .isLessThanOrEqualTo(1);
        } finally {
            // Release the 100 controlled dispatch futures so nothing is left pending.
            backend.complete(DispatchResult.warm(InvocationResult.success("done")));
        }
    }
}
