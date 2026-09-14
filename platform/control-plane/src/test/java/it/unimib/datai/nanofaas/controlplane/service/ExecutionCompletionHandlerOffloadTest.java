package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCompletionHandlerOffloadTest {

    private final ExecutionStore executionStore = new ExecutionStore();
    private final RetryScheduler enqueuer = mock(RetryScheduler.class);
    private final DispatcherRouter dispatcherRouter = mock(DispatcherRouter.class);
    private final TestDispatchOwnership ownership = new TestDispatchOwnership();
    private final ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
            executionStore, enqueuer, dispatcherRouter, new Metrics(new SimpleMeterRegistry()));

    private ExecutionRecord executionRecord(String executionId, String functionName) {
        FunctionSpec spec = new FunctionSpec(functionName, "img", List.of(), Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(executionId, functionName, spec,
                new InvocationRequest("p", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC
            );
        ExecutionRecord executionRecord = new ExecutionRecord(executionId, task);
        executionStore.put(executionRecord);
        return executionRecord;
    }

    @Test
    void offloadedSuccessCompletesWithoutReleasingDispatchSlot() {
        ExecutionRecord executionRecord = executionRecord("exec-ok", "fn");
        // A real owned lease is attached so "released nothing" is falsifiable: the
        // offload path must not touch capacity it never acquired. Offload has no
        // lease in production, so this is the strongest available probe.
        ownership.attach(executionRecord);

        InvocationResult result = InvocationResult.success("remote-out");
        handler.completeOffloadedExecution("exec-ok", result);

        assertThat(ownership.releases()).isZero();
        verify(enqueuer, never()).enqueue(any());
        assertThat(executionRecord.completion()).isCompletedWithValue(result);
    }

    @Test
    void offloadedFunctionErrorCompletesWithoutRetry() {
        ExecutionRecord executionRecord = executionRecord("exec-err", "fn");

        InvocationResult remoteError = InvocationResult.error("BOOM", "remote function failed");
        handler.completeOffloadedExecution("exec-err", remoteError);

        verify(enqueuer, never()).enqueue(any());
        verify(dispatcherRouter, never()).dispatchLocal(any());
        assertThat(executionRecord.completion()).isCompletedWithValue(remoteError);
    }

    @Test
    void offloadInfraFailureCompletesExceptionallyWithoutRetryOrSlotRelease() {
        ExecutionRecord executionRecord = executionRecord("exec-fail", "fn");
        ownership.attach(executionRecord);

        OffloadFailedException failure = new OffloadFailedException("http://cloud:8080", false, "unreachable");
        handler.failOffloadedExecution("exec-fail", failure);

        verify(enqueuer, never()).enqueue(any());
        assertThat(ownership.releases()).isZero();
        verify(dispatcherRouter, never()).dispatchLocal(any());
        assertThat(executionRecord.completion().isCompletedExceptionally()).isTrue();
        assertThat(executionRecord.snapshot().lastError().code()).isEqualTo(OffloadGateway.OFFLOAD_FAILED_CODE);
    }

    @Test
    void ordinaryErrorStillRetries() {
        ExecutionRecord executionRecord = executionRecord("exec-plain", "fn2");
        when(enqueuer.enqueue(any())).thenReturn(true);

        handler.completeExecution("exec-plain", InvocationResult.error("BOOM", "transient"));

        verify(enqueuer).enqueue(any());
        assertThat(executionRecord.completion()).isNotCompleted();
    }

    @Test
    void retrySchedulingErrorConcludesInsteadOfParkingTheRecord() {
        ExecutionRecord executionRecord = executionRecord("exec-retry-error", "fn3");
        when(enqueuer.enqueue(any())).thenThrow(new AssertionError("scheduler failed"));

        assertThatCode(() -> handler.completeExecution(
                "exec-retry-error", InvocationResult.error("BOOM", "transient")))
                .doesNotThrowAnyException();

        assertThat(executionRecord.completion()).isCompleted();
        assertThat(executionRecord.state()).isEqualTo(
                it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR);
        assertThat(executionStore.getOrNull("exec-retry-error")).isNull();
    }
}
