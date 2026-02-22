package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceRetryQueueFullTest {

    @Mock private FunctionService functionService;
    @Mock private InvocationEnqueuer enqueuer;
    @Mock private Metrics metrics;
    @Mock private DispatcherRouter dispatcherRouter;
    @Mock private SyncQueueGateway syncQueueGateway;

    private ExecutionStore executionStore;
    private IdempotencyStore idempotencyStore;
    private RateLimiter rateLimiter;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);

        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
                executionStore, enqueuer, dispatcherRouter, metrics);

        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, idempotencyStore,
                rateLimiter, metrics, syncQueueGateway, completionHandler
        );

        FunctionSpec testSpec = new FunctionSpec(
                "testFunc", "test-image", null, null, null,
                30000, 4, 100, 3, null, ExecutionMode.LOCAL, null, null, null
        );

        when(functionService.get("testFunc")).thenReturn(Optional.of(testSpec));
        when(enqueuer.enabled()).thenReturn(true);
        when(syncQueueGateway.enabled()).thenReturn(false);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry simpleMeterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        when(metrics.latency(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-latency").register(simpleMeterRegistry));
        when(metrics.queueWait(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-queue-wait").register(simpleMeterRegistry));
        when(metrics.e2eLatency(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-e2e").register(simpleMeterRegistry));
        when(metrics.initDuration(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-init").register(simpleMeterRegistry));
    }

    @Test
    void retryWithQueueFull_completesFutureWithError() {
        // Initial enqueue succeeds
        when(enqueuer.enqueue(any())).thenReturn(true);

        InvocationResponse response = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), null, null
        );

        ExecutionRecord record = executionStore.get(response.executionId()).orElseThrow();
        assertThat(record.completion().isDone()).isFalse();

        // Now make queue reject on retry
        when(enqueuer.enqueue(any())).thenReturn(false);

        // Complete with error - should attempt retry but queue is full
        InvocationResult errorResult = InvocationResult.error("ERROR", "First attempt failed");
        invocationService.completeExecution(response.executionId(), errorResult);

        // Future SHOULD be completed with the error since retry failed
        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);

        InvocationResult result = record.completion().join();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("ERROR");
        verify(enqueuer).releaseDispatchSlot("testFunc");
    }

    @Test
    void retryWithQueueFull_afterSuccessfulRetries_completesFuture() {
        // Initial enqueue and first retry succeed, second retry fails
        when(enqueuer.enqueue(any()))
                .thenReturn(true)   // initial enqueue
                .thenReturn(true)   // first retry enqueue
                .thenReturn(false); // second retry enqueue fails

        InvocationResponse response = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), null, null
        );

        ExecutionRecord record = executionStore.get(response.executionId()).orElseThrow();

        // First failure -> retry (attempt 2)
        invocationService.completeExecution(
                response.executionId(), InvocationResult.error("ERROR", "Attempt 1")
        );
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.task().attempt()).isEqualTo(2);

        // Second failure -> retry attempt but queue full
        invocationService.completeExecution(
                response.executionId(), InvocationResult.error("ERROR", "Attempt 2")
        );

        // Future should be completed because retry queue was full
        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        verify(enqueuer, times(2)).releaseDispatchSlot("testFunc");
    }
}
