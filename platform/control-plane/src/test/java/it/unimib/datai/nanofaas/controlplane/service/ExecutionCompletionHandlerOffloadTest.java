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
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCompletionHandlerOffloadTest {

    private final ExecutionStore executionStore = new ExecutionStore();
    private final InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
    private final DispatcherRouter dispatcherRouter = mock(DispatcherRouter.class);
    private final ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
            executionStore, enqueuer, dispatcherRouter, new Metrics(new SimpleMeterRegistry()));

    private ExecutionRecord record(String executionId, String functionName) {
        FunctionSpec spec = new FunctionSpec(functionName, "img", List.of(), Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(executionId, functionName, spec,
                new InvocationRequest("p", Map.of()), null, null, Instant.now(), 1);
        ExecutionRecord record = new ExecutionRecord(executionId, task);
        executionStore.put(record);
        return record;
    }

    @Test
    void offloadedSuccessCompletesWithoutReleasingDispatchSlot() {
        ExecutionRecord record = record("exec-ok", "fn");
        when(enqueuer.enabled()).thenReturn(true);

        InvocationResult result = InvocationResult.success("remote-out");
        handler.completeOffloadedExecution("exec-ok", result);

        // offloaded calls never acquired a slot: releasing one would corrupt
        // the local concurrency accounting
        verify(enqueuer, never()).releaseDispatchSlot(anyString());
        verify(enqueuer, never()).enqueue(any());
        assertThat(record.completion()).isCompletedWithValue(result);
    }

    @Test
    void offloadedFunctionErrorCompletesWithoutRetry() {
        ExecutionRecord record = record("exec-err", "fn");
        when(enqueuer.enabled()).thenReturn(true);

        InvocationResult remoteError = InvocationResult.error("BOOM", "remote function failed");
        handler.completeOffloadedExecution("exec-err", remoteError);

        verify(enqueuer, never()).enqueue(any());
        verify(dispatcherRouter, never()).dispatchLocal(any());
        assertThat(record.completion()).isCompletedWithValue(remoteError);
    }

    @Test
    void offloadInfraFailureCompletesExceptionallyWithoutRetryOrSlotRelease() {
        ExecutionRecord record = record("exec-fail", "fn");
        when(enqueuer.enabled()).thenReturn(true);

        OffloadFailedException failure = new OffloadFailedException("http://cloud:8080", false, "unreachable");
        handler.failOffloadedExecution("exec-fail", failure);

        verify(enqueuer, never()).enqueue(any());
        verify(enqueuer, never()).releaseDispatchSlot(anyString());
        verify(dispatcherRouter, never()).dispatchLocal(any());
        assertThat(record.completion().isCompletedExceptionally()).isTrue();
        assertThat(record.snapshot().lastError().code()).isEqualTo(OffloadGateway.OFFLOAD_FAILED_CODE);
    }

    @Test
    void ordinaryErrorStillRetries() {
        ExecutionRecord record = record("exec-plain", "fn2");
        when(enqueuer.enabled()).thenReturn(true);
        when(enqueuer.enqueue(any())).thenReturn(true);

        handler.completeExecution("exec-plain", InvocationResult.error("BOOM", "transient"));

        verify(enqueuer).enqueue(any());
        assertThat(record.completion()).isNotCompleted();
    }
}
