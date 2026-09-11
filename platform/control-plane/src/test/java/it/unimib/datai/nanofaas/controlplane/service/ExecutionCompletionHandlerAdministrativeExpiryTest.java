package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gap this closes: before this change, {@code ExecutionStore}'s {@code inFlight}
 * cache evicted a stuck record on {@code maxLifetime} with nobody told - no active
 * background sweep at all (eviction only happened on the next read/write), and even
 * an opportunistic eviction completed no future, released no slot and archived no
 * outcome. A lost dispatch callback (crashed runtime, dropped response) held its
 * concurrency slot and left its caller's shared future pending forever.
 */
class ExecutionCompletionHandlerAdministrativeExpiryTest {
    private final TestDispatchOwnership ownership = new TestDispatchOwnership();

    private static final Duration SHORT_MAX_LIFETIME = Duration.ofMillis(100);

    @Test
    void aRecordThatNeverDispatchesExpiresWithoutReleasingAnySlot() {
        ExecutionStore store = shortLivedStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        new ExecutionCompletionHandler(store, enqueuer::enqueue, mock(DispatcherRouter.class), new Metrics(new SimpleMeterRegistry()));
        // Never dispatched: task expired while still sitting in a queue, or served
        // by offload - both never call dispatch(), so no slot was ever acquired.
        ExecutionRecord executionRecord = new ExecutionRecord("exec-queued", task("exec-queued", "fn"));
        store.put(executionRecord);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(executionRecord.completion()).isDone();
            assertThat(store.outcomeOf("exec-queued")).isNotNull();
        });

        // "Without releasing any slot" is now structural rather than observable here: the
        // record never dispatched, so no capacity lease was ever attached and the release
        // path has nothing to take. The former name-based counter cannot move for this
        // fixture, so the assertions below (and the ones above) carry the behaviour.
        assertThat(executionRecord.holdsDispatchLease()).isFalse();
        InvocationResult result = executionRecord.completion().join();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ExecutionCompletionHandler.EXECUTION_EXPIRED_CODE);
        assertThat(store.outcomeOf("exec-queued").state()).isEqualTo(ExecutionState.ERROR);
    }

    @Test
    void aStuckDispatchExpiresReleasingItsSlotExactlyOnceAndConcludesTheWaiter() {
        ExecutionStore store = shortLivedStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        DispatcherRouter dispatcherRouter = mock(DispatcherRouter.class);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer::enqueue, dispatcherRouter, new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-stuck", "fn");
        // A dispatch that never calls back - the crashed-runtime / dropped-response case.
        CompletableFuture<DispatchResult> neverCompletes = new CompletableFuture<>();
        when(dispatcherRouter.dispatchExternal(any(InvocationTask.class))).thenReturn(neverCompletes);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);

        handler.dispatch(ownership.acquire(task));
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.RUNNING);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(executionRecord.completion()).isDone();
            assertThat(store.outcomeOf("exec-stuck")).isNotNull();
        });

        assertThat(ownership.releases())
                .as("the slot the abandoned dispatch was holding must come back exactly once")
                .isEqualTo(1);
        assertThat(executionRecord.completion().join().success()).isFalse();

        // The real dispatch outcome finally shows up, long after expiry archived the
        // record. It must be a harmless no-op: no exception, and definitely no second release.
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("too-late")));
        assertThat(ownership.releases()).isEqualTo(1);
    }

    @Test
    void anExecutionTimeoutDoesNotStopAdministrativeExpiryFromConcludingTheSharedFuture() {
        ExecutionStore store = shortLivedStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        DispatcherRouter dispatcherRouter = mock(DispatcherRouter.class);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer::enqueue, dispatcherRouter, new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-timeout-then-expired", "fn");
        CompletableFuture<DispatchResult> neverCompletes = new CompletableFuture<>();
        when(dispatcherRouter.dispatchExternal(any(InvocationTask.class))).thenReturn(neverCompletes);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        handler.dispatch(ownership.acquire(task));

        // An execution-level deadline (not a single waiter's budget) marks the record
        // TIMEOUT while the dispatch (and its slot) is still in flight. The administrative
        // expiry must still conclude the shared future and give the slot back, but never
        // overwrite the already-recorded terminal state.
        executionRecord.markTimeout();
        assertThat(store.getOrNull("exec-timeout-then-expired")).isNotNull();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(executionRecord.completion()).isDone());

        // The recorded state is the invariant a duplicate-completion test elsewhere
        // already protects: TIMEOUT, not overwritten by the administrative fallback.
        assertThat(store.outcomeOf("exec-timeout-then-expired").state()).isEqualTo(ExecutionState.TIMEOUT);
        assertThat(ownership.releases())
                .as("the dispatch that outlived the execution timeout still held a slot")
                .isEqualTo(1);
    }

    private static ExecutionStore shortLivedStore() {
        return new ExecutionStore(
                ExecutionStoreProperties.of(Duration.ofMinutes(5), SHORT_MAX_LIFETIME, Duration.ofSeconds(30), 100_000),
                new SimpleMeterRegistry());
    }

    private static InvocationTask task(String executionId, String functionName) {
        FunctionSpec spec = new FunctionSpec(
                functionName, "test-image", null, null, null,
                30_000, 1, 100, 2, null, ExecutionMode.EXTERNAL, null, null, null
        );
        return new InvocationTask(
                executionId, functionName, spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC
        );
    }

    private static final class CountingEnqueuer implements RetryScheduler {
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public boolean enqueue(InvocationTask task) {
            return true;
        }

    }
}
