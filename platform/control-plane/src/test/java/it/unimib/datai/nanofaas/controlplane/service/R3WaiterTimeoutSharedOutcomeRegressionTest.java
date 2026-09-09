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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for finding R3 of the 2026-09-08 pre-soak review.
 *
 * <p>{@link ReactiveInvocationCoordinator#invoke} marks the SHARED execution record
 * {@code TIMEOUT} when a single subscriber's wait budget expires
 * ({@code suppressCancel=true} protects the shared future but not the shared state).
 * When a short waiter times out while a long waiter still owns the same key, and the
 * backend then answers success, the completion handler deliberately preserves the
 * record's TIMEOUT state and settles that as the shared outcome: the long waiter
 * receives the real success on the future, but a later replay of the same execution
 * reads the archived TIMEOUT and returns it — one caller is answered with success
 * while the idempotent replay of the same answer is permanently forgotten.
 *
 * <p>Correct behavior (plan task P01/P05, invariant I1): a waiter timeout concludes
 * only that waiter's response. The invocation keeps a single replayable terminal
 * outcome, so the long waiter and the replay both receive the real backend answer.
 *
 * <p>This test asserts the desired behavior, so it is RED on the current baseline,
 * where the replay receives {@code timeout}.
 */
class R3WaiterTimeoutSharedOutcomeRegressionTest {

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationExecutionFactory.ExecutionLookup lookup(
            InvocationExecutionFactory factory, String key) {
        return factory.createOrReuseExecution("fn", spec("fn"),
                new InvocationRequest("payload", Map.of()), key, null, InvocationKind.SYNC);
    }

    @Test
    void shortWaiterTimeoutDoesNotTurnTheSharedSuccessIntoATimeoutForReplays() throws Exception {
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

        InvocationExecutionFactory.ExecutionLookup first = lookup(factory, "key");
        CompletableFuture<SyncInvocation> longWaiter =
                coordinator.invoke(first, spec("fn"), 10_000).toFuture();

        // A second waiter with the same key and a 1 ms budget times out and, on the
        // current baseline, marks the SHARED record TIMEOUT.
        SyncInvocation shortWaiter =
                coordinator.invoke(lookup(factory, "key"), spec("fn"), 1)
                        .block(Duration.ofSeconds(5));
        assertThat(shortWaiter.response().status()).isEqualTo("timeout");

        backend.complete(DispatchResult.warm(InvocationResult.success("real answer")));

        SyncInvocation longResult = longWaiter.get(5, TimeUnit.SECONDS);
        assertThat(longResult.response().status()).isEqualTo("success");

        SyncInvocation replay = coordinator.invoke(lookup(factory, "key"), spec("fn"), 10_000)
                .block(Duration.ofSeconds(5));
        assertThat(replay.response().status())
                .as("replay of an execution whose backend answered success must observe that "
                        + "success, not the short waiter's timeout")
                .isEqualTo("success");
    }
}
