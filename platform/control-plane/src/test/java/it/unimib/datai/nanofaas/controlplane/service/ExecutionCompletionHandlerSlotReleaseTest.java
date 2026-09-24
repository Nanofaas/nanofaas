package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionCompletionHandlerSlotReleaseTest {
    private final TestDispatchOwnership ownership = new TestDispatchOwnership();

    @Test
    void completeExecution_duplicateTerminalCallback_releasesDispatchSlotOnlyOnce() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                mock(DispatcherRouter.class),
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask task = task("exec-duplicate", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("late-duplicate")));

        assertThat(ownership.releases()).isEqualTo(1);
    }

    @Test
    void completeExecution_retryResetsSlotReleaseForNextAttempt() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                mock(DispatcherRouter.class),
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask task = task("exec-retry", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error("ERR", "first")));
        ownership.attach(executionRecord);
        executionRecord.markRunning();
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(ownership.releases()).isEqualTo(2);
    }

    @Test
    void dispatch_staleCallbackAfterRetryReset_doesNotConsumeNextAttemptRelease() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        DispatcherRouter dispatcherRouter = mock(DispatcherRouter.class);
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                dispatcherRouter,
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask attempt1Task = task("exec-stale-callback", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(attempt1Task.executionId(), attempt1Task);
        store.put(executionRecord);

        CompletableFuture<DispatchResult> failedAttempt1 = new CompletableFuture<>();
        CompletableFuture<DispatchResult> staleAttempt1 = new CompletableFuture<>();
        CompletableFuture<DispatchResult> successfulAttempt2 = new CompletableFuture<>();
        when(dispatcherRouter.dispatchLocal(any(InvocationTask.class)))
                .thenReturn(failedAttempt1, staleAttempt1, successfulAttempt2);

        handler.dispatch(ownership.acquire(attempt1Task));
        handler.dispatch(ownership.acquire(attempt1Task));

        failedAttempt1.complete(DispatchResult.warm(InvocationResult.error("ERR", "first")));
        assertThat(ownership.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        staleAttempt1.complete(DispatchResult.warm(InvocationResult.success("late-duplicate")));
        assertThat(ownership.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        handler.dispatch(ownership.acquire(executionRecord.task()));
        successfulAttempt2.complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(ownership.releases()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void completeExecution_publicCallbackWithStaleAttempt_doesNotMutateNextAttempt() throws Exception {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                mock(DispatcherRouter.class),
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask task = task("exec-public-stale", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        completeExecution(handler, task.executionId(), InvocationResult.error("ERR", "first"), 1);
        assertThat(ownership.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        ownership.attach(executionRecord);
        executionRecord.markRunning();
        completeExecution(handler, task.executionId(), InvocationResult.success("late-duplicate"), 1);
        assertThat(ownership.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.RUNNING);
        assertThat(executionRecord.completion()).isNotDone();

        completeExecution(handler, task.executionId(), InvocationResult.success("ok"), 2);
        assertThat(ownership.releases()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(executionRecord.completion()).isDone();
    }

    @Test
    void completeExecution_withoutAttemptUsesCurrentAttemptInsideCompletionLock() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                mock(DispatcherRouter.class),
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask task = task("exec-legacy-race", "fn");
        MutatingExecutionRecord executionRecord = new MutatingExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        executionRecord.markRunning();
        executionRecord.mutateToNextAttemptOnNextTaskRead();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(ownership.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(executionRecord.completion()).isDone();
    }

    private static void completeExecution(ExecutionCompletionHandler handler,
                                          String executionId,
                                          InvocationResult result,
                                          Integer completedAttempt) throws Exception {
        Method method = ExecutionCompletionHandler.class.getMethod(
                "completeExecution",
                String.class,
                InvocationResult.class,
                Integer.class
        );
        method.invoke(handler, executionId, result, completedAttempt);
    }

    private static InvocationTask task(String executionId, String functionName) {
        FunctionSpec spec = new FunctionSpec(
                functionName,
                "test-image",
                null,
                null,
                null,
                30_000,
                1,
                100,
                2,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        return new InvocationTask(
                executionId,
                functionName,
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
    }

    @Test
    void completeExecution_afterASyncTimeout_releasesTheSlotAndOnlyThenSettles() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, enqueuer, mock(DispatcherRouter.class), new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-timeout", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        // An execution-level timeout marks the record terminal while the dispatch is still
        // in flight (and still holding its slot). The completion arriving later must give
        // that slot back before settling: archiving first would take the record out of the
        // living, and the completion would no longer find it to release the slot.
        executionRecord.markTimeout();
        assertThat(store.getOrNull("exec-timeout")).isNotNull();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("tardi")));

        assertThat(ownership.releases()).isEqualTo(1);
        // And only now, with the slot given back, does the outcome take the record's place.
        assertThat(store.getOrNull("exec-timeout")).isNull();
        assertThat(store.outcomeOf("exec-timeout")).isNotNull();
    }

    @Test
    void completeExecution_afterAnExecutionTimeout_doesNotOverwriteTheAlreadyConcludedFuture() {
        ExecutionStore store = new ExecutionStore();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, new CountingEnqueuer(), mock(DispatcherRouter.class), new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-shared", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        // An execution-level timeout (not a single waiter's budget) concludes the shared
        // future with the timeout result before the dispatch outcome arrives. A late
        // success must not overwrite either the future or the recorded terminal state:
        // the already-definitive result prevails over late responses (invariant I1).
        executionRecord.markTimeout();
        executionRecord.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("the real answer")));

        assertThat(executionRecord.completion().isDone()).isTrue();
        assertThat(executionRecord.completion().join().success()).isFalse();
        assertThat(executionRecord.completion().join().error().code()).isEqualTo("QUEUE_TIMEOUT");
        // The recorded state is not rewritten: that is the long-standing invariant.
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.TIMEOUT);
    }

    @Test
    void completeExecution_whileRetryingDoesNotCompleteTheFutureEarly() {
        ExecutionStore store = new ExecutionStore();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, new CountingEnqueuer(), mock(DispatcherRouter.class), new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-retry", "fn");  // maxRetries = 2 in the fixture
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error("ERR", "primo tentativo")));

        // The record went back to the queue: the future must not have been touched.
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(store.getOrNull("exec-retry")).isNotNull();
    }

    private static final class CountingEnqueuer implements RetryScheduler {
        @Override
        public boolean enqueue(InvocationTask task, java.time.Instant notBefore, Runnable onRejected) {
            return true;
        }

    }

    private final class MutatingExecutionRecord extends ExecutionRecord {
        private boolean mutateToNextAttemptOnNextTaskRead;

        private MutatingExecutionRecord(String executionId, InvocationTask task) {
            super(executionId, task);
        }

        synchronized void mutateToNextAttemptOnNextTaskRead() {
            mutateToNextAttemptOnNextTaskRead = true;
        }

        @Override
        public synchronized InvocationTask task() {
            InvocationTask current = super.task();
            if (mutateToNextAttemptOnNextTaskRead) {
                mutateToNextAttemptOnNextTaskRead = false;
                resetForRetry(new InvocationTask(
                        current.executionId(),
                        current.functionName(),
                        current.functionSpec(),
                        current.request(),
                        current.idempotencyKey(),
                        current.traceId(),
                        Instant.now(),
                        current.attempt() + 1
                ,
        InvocationKind.SYNC
    ));
                ownership.attach(this);
                markRunning();
            }
            return current;
        }
    }
}
