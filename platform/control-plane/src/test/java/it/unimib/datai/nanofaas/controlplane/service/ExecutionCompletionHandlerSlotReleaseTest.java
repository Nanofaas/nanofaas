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
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        executionRecord.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("late-duplicate")));

        assertThat(enqueuer.releases()).isEqualTo(1);
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
        executionRecord.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error("ERR", "first")));
        executionRecord.markRunning();
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(enqueuer.releases()).isEqualTo(2);
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

        handler.dispatch(attempt1Task);
        handler.dispatch(attempt1Task);

        failedAttempt1.complete(DispatchResult.warm(InvocationResult.error("ERR", "first")));
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        staleAttempt1.complete(DispatchResult.warm(InvocationResult.success("late-duplicate")));
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        handler.dispatch(executionRecord.task());
        successfulAttempt2.complete(DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(enqueuer.releases()).isEqualTo(2);
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
        executionRecord.markRunning();

        completeExecution(handler, task.executionId(), InvocationResult.error("ERR", "first"), 1);
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        executionRecord.markRunning();
        completeExecution(handler, task.executionId(), InvocationResult.success("late-duplicate"), 1);
        assertThat(enqueuer.releases()).isEqualTo(1);
        assertThat(executionRecord.task().attempt()).isEqualTo(2);
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.RUNNING);
        assertThat(executionRecord.completion()).isNotDone();

        completeExecution(handler, task.executionId(), InvocationResult.success("ok"), 2);
        assertThat(enqueuer.releases()).isEqualTo(2);
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

        assertThat(enqueuer.releases()).isEqualTo(1);
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
        executionRecord.markRunning();

        // Il percorso sincrono esaurisce il suo budget mentre il dispatch e' ancora
        // in volo: marca il record e basta. Archiviarlo qui lo toglierebbe dai vivi,
        // e il completamento che arriva dopo non lo troverebbe piu' per restituire
        // lo slot di concorrenza che quel dispatch sta ancora tenendo.
        executionRecord.markTimeout();
        assertThat(store.getOrNull("exec-timeout")).isNotNull();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("tardi")));

        assertThat(enqueuer.releases()).isEqualTo(1);
        // E solo adesso, a slot restituito, l'esito prende il posto del record.
        assertThat(store.getOrNull("exec-timeout")).isNull();
        assertThat(store.outcomeOf("exec-timeout")).isNotNull();
    }

    @Test
    void completeExecution_afterATimeout_stillAnswersWhoeverIsWaitingOnTheSharedFuture() {
        ExecutionStore store = new ExecutionStore();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, new CountingEnqueuer(), mock(DispatcherRouter.class), new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-shared", "fn");
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        executionRecord.markRunning();

        // Il chiamante A esaurisce il suo budget. Il chiamante B - stessa chiave di
        // idempotenza, stesso record, budget piu' largo - e' ancora sulla future.
        executionRecord.markTimeout();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("risposta vera")));

        assertThat(executionRecord.completion().isDone())
                .as("B aspetterebbe invano fino al proprio timeout")
                .isTrue();
        assertThat(executionRecord.completion().join().output()).isEqualTo("risposta vera");
        // Lo stato registrato non viene riscritto: e' l'invariante di sempre.
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.TIMEOUT);
    }

    @Test
    void completeExecution_whileRetryingDoesNotCompleteTheFutureEarly() {
        ExecutionStore store = new ExecutionStore();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store, new CountingEnqueuer(), mock(DispatcherRouter.class), new Metrics(new SimpleMeterRegistry()));
        InvocationTask task = task("exec-retry", "fn");  // maxRetries = 2 nella fixture
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        store.put(executionRecord);
        executionRecord.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error("ERR", "primo tentativo")));

        // Il record e' tornato in coda: la future non deve essere stata toccata.
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(store.getOrNull("exec-retry")).isNotNull();
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
                ,
        InvocationKind.SYNC
    ));
                markRunning();
            }
            return current;
        }
    }
}
