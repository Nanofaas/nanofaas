package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for finding R4 of the 2026-09-08 pre-soak review.
 *
 * <p>Both {@code completeOffloadedExecution} and {@code failOffloadedExecution} in
 * {@link ExecutionCompletionHandler} return immediately when the record is already
 * terminal. A short idempotent waiter can mark the shared record TIMEOUT while the
 * long owner's remote call is still running; when the remote result then arrives, the
 * completion handler finds the terminal record and returns early, leaving a finished
 * offloaded call live in {@code ExecutionStore.inFlight} with a pending shared future.
 * Nothing settles it until the 30-minute administrative expiry.
 *
 * <p>Correct behavior (plan task P04/P05, invariant I3): a terminal state must not be
 * an early return that leaves the future or the store open. The remote result must
 * conclude the local attempt — complete the shared future and settle the record — so
 * a finished offloaded call is never retained in the live store.
 *
 * <p>This test asserts the desired behavior, so it is RED on the current baseline,
 * where the live record and the pending shared future remain after the remote success.
 */
class R4OffloadCompletionAfterWaiterTimeoutRegressionTest {

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
    void offloadedResultArrivingAfterAShortWaiterTimeoutStillConcludesTheLocalAttempt() {
        ExecutionStore store = new ExecutionStore();
        IdempotencyStore keys = new IdempotencyStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        Sinks.One<InvocationResult> remote = Sinks.one();
        OffloadGateway gateway = new OffloadGateway() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public boolean shouldOffloadEagerly(FunctionSpec s) {
                return true;
            }

            @Override
            public boolean shouldOffloadOnPressure(FunctionSpec s) {
                return false;
            }

            @Override
            public String targetUrl(FunctionSpec s) {
                return "http://remote";
            }

            @Override
            public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger,
                                                       OffloadContext context, int budget) {
                return remote.asMono();
            }
        };
        TestWaiterCapacity.Runtime runtime = TestWaiterCapacity.runtime(store, keys, metrics, "fn");
        InvocationExecutionFactory factory = runtime.factory();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(store, null,
                new DispatcherRouter(new LocalDispatcher(), null), metrics);
        ReactiveInvocationCoordinator coordinator =
                new ReactiveInvocationCoordinator(null, metrics, null, gateway, handler,
                        new InvocationResponseMapper(), runtime.waiters());

        InvocationExecutionFactory.ExecutionLookup first = lookup(factory, "offloaded");
        CompletableFuture<SyncInvocation> owner =
                coordinator.invoke(first, spec("fn"), 10_000).toFuture();

        // A shorter idempotent waiter times out while the owner's remote call is pending
        // and, on the current baseline, marks the shared record TIMEOUT.
        coordinator.invoke(lookup(factory, "offloaded"), spec("fn"), 1)
                .block(Duration.ofSeconds(5));

        try {
            // The remote call now finishes successfully. The finished call must not stay
            // live: the shared future completes and the record is settled (archived).
            remote.tryEmitValue(InvocationResult.success("remote success"));
            assertThat(store.inFlightCount())
                    .as("a finished offloaded execution must not remain in the live store")
                    .isZero();
            assertThat(first.executionRecord().completion())
                    .as("the shared completion future must be concluded by the remote result")
                    .isDone();
            assertThat(owner)
                    .as("the original caller must observe the remote result")
                    .isDone();
        } finally {
            owner.cancel(true);
        }
    }
}
