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
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionCompletionHandlerSlotReleaseTest {

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
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        record.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("late-duplicate")));

        assertThat(enqueuer.releases()).isEqualTo(1);
        store.shutdown();
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
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        record.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error("ERR", "first")));
        record.markRunning();
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(enqueuer.releases()).isEqualTo(2);
        store.shutdown();
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
        ExecutionRecord record = new ExecutionRecord(attempt1Task.executionId(), attempt1Task);
        store.put(record);

        CompletableFuture<DispatchResult> failedAttempt1 = new CompletableFuture<>();
        CompletableFuture<DispatchResult> staleAttempt1 = new CompletableFuture<>();
        CompletableFuture<DispatchResult> successfulAttempt2 = new CompletableFuture<>();
        when(dispatcherRouter.dispatchLocal(any(InvocationTask.class)))
                .thenReturn(failedAttempt1, staleAttempt1, successfulAttempt2);

        handler.dispatch(attempt1Task);
        handler.dispatch(attempt1Task);

        failedAttempt1.complete(DispatchResult.warm(InvocationResult.error("ERR", "first")));
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        staleAttempt1.complete(DispatchResult.warm(InvocationResult.success("late-duplicate")));
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        handler.dispatch(record.task());
        successfulAttempt2.complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(enqueuer.releases()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        store.shutdown();
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
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        record.markRunning();

        completeExecution(handler, task.executionId(), InvocationResult.error("ERR", "first"), 1);
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        record.markRunning();
        completeExecution(handler, task.executionId(), InvocationResult.success("late-duplicate"), 1);
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.RUNNING);
        assertThat(record.completion()).isNotDone();

        completeExecution(handler, task.executionId(), InvocationResult.success("ok"), 2);
        assertThat(enqueuer.releases()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.completion()).isDone();
        store.shutdown();
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
        MutatingExecutionRecord record = new MutatingExecutionRecord(task.executionId(), task);
        store.put(record);
        record.markRunning();
        record.mutateToNextAttemptOnNextTaskRead();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(record.task().attempt()).isEqualTo(2);
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.completion()).isDone();
        store.shutdown();
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
        );
    }

    private static final class CountingEnqueuer implements InvocationEnqueuer {
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public boolean enqueue(InvocationTask task) {
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void releaseDispatchSlot(String functionName) {
            releases.incrementAndGet();
        }

        int releases() {
            return releases.get();
        }
    }

    private static final class MutatingExecutionRecord extends ExecutionRecord {
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
                ));
                markRunning();
            }
            return current;
        }
    }
}
