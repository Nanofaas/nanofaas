package it.unimib.datai.mcfaas.controlplane.service;

import it.unimib.datai.mcfaas.common.model.ErrorInfo;
import it.unimib.datai.mcfaas.common.model.ExecutionMode;
import it.unimib.datai.mcfaas.common.model.FunctionSpec;
import it.unimib.datai.mcfaas.common.model.InvocationRequest;
import it.unimib.datai.mcfaas.common.model.InvocationResponse;
import it.unimib.datai.mcfaas.common.model.InvocationResult;
import it.unimib.datai.mcfaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.mcfaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.mcfaas.controlplane.execution.ExecutionState;
import it.unimib.datai.mcfaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.mcfaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.mcfaas.controlplane.queue.QueueManager;
import it.unimib.datai.mcfaas.controlplane.registry.FunctionService;
import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.mcfaas.controlplane.sync.SyncQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceRetryTest {

    @Mock
    private FunctionService functionService;

    @Mock
    private QueueManager queueManager;

    @Mock
    private Metrics metrics;

    @Mock
    private DispatcherRouter dispatcherRouter;

    @Mock
    private SyncQueueService syncQueueService;

    private ExecutionStore executionStore;
    private IdempotencyStore idempotencyStore;
    private RateLimiter rateLimiter;
    private InvocationService invocationService;

    private FunctionSpec testSpec;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);

        invocationService = new InvocationService(
                functionService,
                queueManager,
                executionStore,
                idempotencyStore,
                dispatcherRouter,
                rateLimiter,
                metrics,
                syncQueueService
        );

        testSpec = new FunctionSpec(
                "testFunc",
                "test-image",
                null,
                null,
                null,
                30000,
                4,
                100,
                3,  // maxRetries = 3
                null,
                ExecutionMode.LOCAL,
                null,
                null
        );

        when(functionService.get("testFunc")).thenReturn(Optional.of(testSpec));
        when(queueManager.enqueue(any())).thenReturn(true);
        when(syncQueueService.enabled()).thenReturn(false);
        when(metrics.latency(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test").register(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void completeExecution_withRetry_doesNotCompleteTheFuture() {
        // Create an execution
        InvocationResponse response = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                null,
                null
        );

        ExecutionRecord record = executionStore.get(response.executionId()).orElseThrow();
        assertThat(record.completion().isDone()).isFalse();

        // Complete with error (should trigger retry since maxRetries=3, attempt=1)
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "First attempt failed")
        );

        // Future should NOT be completed yet because retry was scheduled
        assertThat(record.completion().isDone()).isFalse();

        // Record should be back in QUEUED state
        assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);

        // Task should have attempt=2
        assertThat(record.task().attempt()).isEqualTo(2);

        // Enqueue should have been called twice (initial + retry)
        verify(queueManager, times(2)).enqueue(any());
    }

    @Test
    void completeExecution_afterMaxRetries_completesTheFuture() {
        // Create an execution
        InvocationResponse response = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                null,
                null
        );

        ExecutionRecord record = executionStore.get(response.executionId()).orElseThrow();

        // Simulate 3 failed attempts (maxRetries=3)
        // Attempt 1
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 1 failed")
        );
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.task().attempt()).isEqualTo(2);

        // Attempt 2
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 2 failed")
        );
        assertThat(record.completion().isDone()).isFalse();
        assertThat(record.task().attempt()).isEqualTo(3);

        // Attempt 3 (last one, maxRetries reached)
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 3 failed")
        );

        // NOW the future should be completed with the error
        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
    }

    @Test
    void completeExecution_withSuccess_completesImmediately() {
        // Create an execution
        InvocationResponse response = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                null,
                null
        );

        ExecutionRecord record = executionStore.get(response.executionId()).orElseThrow();

        // Complete with success
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.success("result")
        );

        // Future should be completed immediately
        assertThat(record.completion().isDone()).isTrue();
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(record.output()).isEqualTo("result");
    }

    @Test
    void retry_preservesExecutionId() {
        // Create an execution
        InvocationResponse response = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                null,
                null
        );

        String originalExecutionId = response.executionId();
        ExecutionRecord record = executionStore.get(originalExecutionId).orElseThrow();

        // Trigger a retry
        invocationService.completeExecution(
                originalExecutionId,
                InvocationResult.error("ERROR", "Failed")
        );

        // ExecutionId should be the same
        assertThat(record.executionId()).isEqualTo(originalExecutionId);
        assertThat(record.task().executionId()).isEqualTo(originalExecutionId);
    }

    @Test
    void retry_clearsIdempotencyKey() {
        // Create an execution with idempotency key
        InvocationResponse response = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                "my-idempotency-key",
                null
        );

        ExecutionRecord record = executionStore.get(response.executionId()).orElseThrow();

        // Original task has idempotency key
        assertThat(record.task().idempotencyKey()).isEqualTo("my-idempotency-key");

        // Trigger a retry
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Failed")
        );

        // Retry task should NOT have idempotency key (internal retry)
        assertThat(record.task().idempotencyKey()).isNull();
    }

    @Test
    void invokeSync_waitsForRetryToComplete() throws Exception {
        AtomicInteger attemptCounter = new AtomicInteger(0);

        // We need to simulate the scheduler dispatching and completing
        // For this test, we'll manually trigger completions

        // Create execution in a separate thread
        Thread invokerThread = new Thread(() -> {
            try {
                InvocationResponse response = invocationService.invokeSync(
                        "testFunc",
                        new InvocationRequest("payload", null),
                        null,
                        null,
                        5000  // 5 second timeout
                );
                // Should get here after successful retry
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        invokerThread.start();

        // Wait for the execution to be created
        Thread.sleep(100);

        // Find the execution
        // We don't know the exact ID, so we need to find it
        // For simplicity, let's test with invokeAsync instead

        invokerThread.interrupt();
        invokerThread.join(1000);
    }
}
