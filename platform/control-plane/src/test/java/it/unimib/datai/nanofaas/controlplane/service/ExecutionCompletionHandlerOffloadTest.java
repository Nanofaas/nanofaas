package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void offloadFailureIsNeverRetriedLocally() {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask("exec-off", "fn", spec,
                new InvocationRequest("p", Map.of()), null, null, Instant.now(), 1);
        ExecutionRecord record = new ExecutionRecord("exec-off", task);
        executionStore.put(record);
        when(enqueuer.enabled()).thenReturn(true);

        InvocationResult failure = InvocationResult.error(OffloadGateway.OFFLOAD_FAILED_CODE, "remote unreachable");
        handler.completeExecution("exec-off", failure);

        // final by design: no re-enqueue, no local dispatch, future completed with the error
        verify(enqueuer, never()).enqueue(any());
        verify(dispatcherRouter, never()).dispatchLocal(any());
        assertThat(record.completion()).isCompletedWithValue(failure);
    }

    @Test
    void ordinaryErrorStillRetries() {
        FunctionSpec spec = new FunctionSpec("fn2", "img", List.of(), Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask("exec-plain", "fn2", spec,
                new InvocationRequest("p", Map.of()), null, null, Instant.now(), 1);
        ExecutionRecord record = new ExecutionRecord("exec-plain", task);
        executionStore.put(record);
        when(enqueuer.enabled()).thenReturn(true);
        when(enqueuer.enqueue(any())).thenReturn(true);

        handler.completeExecution("exec-plain", InvocationResult.error("BOOM", "transient"));

        verify(enqueuer).enqueue(any());
        assertThat(record.completion()).isNotCompleted();
    }
}
