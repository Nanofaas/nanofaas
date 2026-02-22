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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionCompletionHandlerTest {

    @Mock private InvocationEnqueuer enqueuer;
    @Mock private Metrics metrics;
    @Mock private DispatcherRouter dispatcherRouter;

    private ExecutionStore executionStore;
    private ExecutionCompletionHandler completionHandler;

    private FunctionSpec testSpec;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        completionHandler = new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics);

        testSpec = new FunctionSpec(
                "testFunc", "test-image", null, null, null,
                30000, 4, 100, 3, null, ExecutionMode.LOCAL, null, null, null
        );

        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        when(metrics.latency(anyString())).thenReturn(
                io.micrometer.core.instrument.Timer.builder("test-latency").register(meterRegistry));
        when(metrics.queueWait(anyString())).thenReturn(
                io.micrometer.core.instrument.Timer.builder("test-queue-wait").register(meterRegistry));
        when(metrics.e2eLatency(anyString())).thenReturn(
                io.micrometer.core.instrument.Timer.builder("test-e2e").register(meterRegistry));
        when(metrics.initDuration(anyString())).thenReturn(
                io.micrometer.core.instrument.Timer.builder("test-init").register(meterRegistry));
    }

    // ─── dispatch tests ────────────────────────────────────────────────────────

    @Test
    void dispatch_whenExecutionRecordMissing_releasesSlotAndSkipsRouter() {
        InvocationTask missingTask = new InvocationTask(
                "missing-exec", "fn",
                functionSpec("fn", ExecutionMode.LOCAL),
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );

        completionHandler.dispatch(missingTask);

        verify(enqueuer).releaseDispatchSlot("fn");
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void dispatch_whenRouterThrowsSynchronously_completesExecutionWithError() throws Exception {
        InvocationTask task = task("exec-1", "local-fn", ExecutionMode.LOCAL);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        executionStore.put(record);

        when(dispatcherRouter.dispatchLocal(any())).thenThrow(new RuntimeException("router down"));

        assertThatCode(() -> completionHandler.dispatch(task)).doesNotThrowAnyException();

        InvocationResult result = record.completion().get(1, TimeUnit.SECONDS);
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("LOCAL_ERROR");
        assertThat(result.error().message()).contains("router down");
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        verify(enqueuer).releaseDispatchSlot("local-fn");
    }

    @Test
    void dispatch_poolMode_routesToPoolDispatcherAndCompletesSuccess() throws Exception {
        InvocationTask task = task("exec-2", "pool-fn", ExecutionMode.POOL);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        executionStore.put(record);

        when(dispatcherRouter.dispatchPool(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok"))));

        completionHandler.dispatch(task);

        InvocationResult result = record.completion().get(1, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("ok");
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        verify(dispatcherRouter).dispatchPool(task);
    }

    // ─── completeExecution / retry tests ──────────────────────────────────────

    @Test
    void completeExecution_withRetry_doesNotCompleteTheFuture() {
        ExecutionRecord record = recordInStore("exec-retry", testSpec, "idem-key");
        when(enqueuer.enqueue(any())).thenReturn(true);

        completionHandler.completeExecution("exec-retry", InvocationResult.error("ERROR", "First attempt failed"));

        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);
        assertThat(record.task().attempt()).isEqualTo(2);
        verify(enqueuer, times(1)).enqueue(any());
        verify(enqueuer).releaseDispatchSlot("testFunc");
    }

    @Test
    void completeExecution_afterMaxRetries_completesTheFuture() {
        ExecutionRecord record = recordInStore("exec-max", testSpec, null);
        when(enqueuer.enqueue(any())).thenReturn(true);

        // Attempt 1
        completionHandler.completeExecution("exec-max", InvocationResult.error("ERROR", "Attempt 1 failed"));
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.task().attempt()).isEqualTo(2);

        // Attempt 2
        completionHandler.completeExecution("exec-max", InvocationResult.error("ERROR", "Attempt 2 failed"));
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.task().attempt()).isEqualTo(3);

        // Attempt 3 (last one, maxRetries=3)
        completionHandler.completeExecution("exec-max", InvocationResult.error("ERROR", "Attempt 3 failed"));

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        verify(enqueuer, times(3)).releaseDispatchSlot("testFunc");
    }

    @Test
    void completeExecution_withSuccess_completesImmediately() {
        ExecutionRecord record = recordInStore("exec-ok", testSpec, null);

        completionHandler.completeExecution("exec-ok", InvocationResult.success("result"));

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.output()).isEqualTo("result");
        verify(enqueuer).releaseDispatchSlot("testFunc");
    }

    @Test
    void retry_preservesExecutionId() {
        ExecutionRecord record = recordInStore("exec-preserve", testSpec, null);
        when(enqueuer.enqueue(any())).thenReturn(true);

        completionHandler.completeExecution("exec-preserve", InvocationResult.error("ERROR", "Failed"));

        assertThat(record.executionId()).isEqualTo("exec-preserve");
        assertThat(record.task().executionId()).isEqualTo("exec-preserve");
    }

    @Test
    void retry_clearsIdempotencyKey() {
        InvocationTask taskWithKey = new InvocationTask(
                "exec-idem", "testFunc", testSpec,
                new InvocationRequest("payload", null),
                "my-idempotency-key", null, Instant.now(), 1
        );
        ExecutionRecord record = new ExecutionRecord("exec-idem", taskWithKey);
        executionStore.put(record);
        when(enqueuer.enqueue(any())).thenReturn(true);

        assertThat(record.task().idempotencyKey()).isEqualTo("my-idempotency-key");

        completionHandler.completeExecution("exec-idem", InvocationResult.error("ERROR", "Failed"));

        assertThat(record.task().idempotencyKey()).isNull();
    }

    // ─── queue-full retry tests ────────────────────────────────────────────────

    @Test
    void retryWithQueueFull_completesFutureWithError() {
        when(enqueuer.enqueue(any())).thenReturn(false);
        ExecutionRecord record = recordInStore("exec-qfull", testSpec, null);

        completionHandler.completeExecution("exec-qfull", InvocationResult.error("ERROR", "First attempt failed"));

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        InvocationResult result = record.completion().join();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("ERROR");
        verify(enqueuer).releaseDispatchSlot("testFunc");
    }

    @Test
    void retryWithQueueFull_afterSuccessfulRetries_completesFuture() {
        when(enqueuer.enqueue(any()))
                .thenReturn(true)   // first retry succeeds
                .thenReturn(false); // second retry queue full
        ExecutionRecord record = recordInStore("exec-mixed", testSpec, null);

        // First failure → retry (attempt 2)
        completionHandler.completeExecution("exec-mixed", InvocationResult.error("ERROR", "Attempt 1"));
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.task().attempt()).isEqualTo(2);

        // Second failure → retry attempt but queue full
        completionHandler.completeExecution("exec-mixed", InvocationResult.error("ERROR", "Attempt 2"));

        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        verify(enqueuer, times(2)).releaseDispatchSlot("testFunc");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private ExecutionRecord recordInStore(String executionId, FunctionSpec spec, String idempotencyKey) {
        InvocationTask task = new InvocationTask(
                executionId, spec.name(), spec,
                new InvocationRequest("payload", null),
                idempotencyKey, null, Instant.now(), 1
        );
        ExecutionRecord record = new ExecutionRecord(executionId, task);
        executionStore.put(record);
        return record;
    }

    private InvocationTask task(String executionId, String functionName, ExecutionMode mode) {
        return new InvocationTask(
                executionId, functionName, functionSpec(functionName, mode),
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );
    }

    private FunctionSpec functionSpec(String functionName, ExecutionMode mode) {
        return new FunctionSpec(
                functionName, "image", null, Map.of(), null,
                1000, 1, 10, 1, null, mode, null, null, null
        );
    }
}
