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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceRetryTest {
    private final TestDispatchOwnership ownership = new TestDispatchOwnership();

    @Mock
    private FunctionService functionService;

    @Mock
    private InvocationEnqueuer enqueuer;

    @Mock
    private Metrics metrics;

    @Mock
    private DispatcherRouter dispatcherRouter;

    @Mock
    private SyncQueueGateway syncQueueGateway;

    private ExecutionStore executionStore;
    private IdempotencyStore idempotencyStore;
    private InvocationService invocationService;

    private FunctionSpec testSpec;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();

        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
                executionStore, enqueuer::enqueue, dispatcherRouter, metrics);

        invocationService = TestWaiterCapacity.service(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                metrics,
                syncQueueGateway,
                completionHandler,
                "testFunc"
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
                null,
                null
        );

        when(functionService.get("testFunc")).thenReturn(Optional.of(testSpec));
        when(enqueuer.enqueue(any())).thenReturn(true);
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        when(syncQueueGateway.enabled()).thenReturn(false);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry simpleMeterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        when(metrics.latency(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-latency").register(simpleMeterRegistry));
        when(metrics.queueWait(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-queue-wait").register(simpleMeterRegistry));
        when(metrics.e2eLatency(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-e2e").register(simpleMeterRegistry));
        when(metrics.initDuration(anyString())).thenReturn(io.micrometer.core.instrument.Timer.builder("test-init").register(simpleMeterRegistry));
        when(metrics.timers(anyString())).thenAnswer(invocation -> new Metrics.FunctionTimers(
                metrics.latency(invocation.getArgument(0)),
                metrics.initDuration(invocation.getArgument(0)),
                metrics.queueWait(invocation.getArgument(0)),
                metrics.e2eLatency(invocation.getArgument(0))
        ));
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

        ExecutionRecord executionRecord = executionStore.get(response.executionId()).orElseThrow();
        assertThat(executionRecord.completion().isDone()).isFalse();
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        // Complete with error (should trigger retry since maxRetries=3, attempt=1)
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "First attempt failed")
        );

        // Future should NOT be completed yet because retry was scheduled
        assertThat(executionRecord.completion().isDone()).isFalse();

        // Record should be back in QUEUED state
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.QUEUED);

        // Task should have attempt=2
        assertThat(executionRecord.task().attempt()).isEqualTo(2);

        // Enqueue should have been called twice (initial + retry)
        verify(enqueuer, times(2)).enqueue(any());
        assertThat(ownership.releases("testFunc")).isEqualTo(1);
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

        ExecutionRecord executionRecord = executionStore.get(response.executionId()).orElseThrow();

        // Simulate the initial attempt plus 3 retries (maxRetries=3)
        // Attempt 1
        ownership.attach(executionRecord);
        executionRecord.markRunning();
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 1 failed")
        );
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(executionRecord.task().attempt()).isEqualTo(2);

        // Attempt 2
        ownership.attach(executionRecord);
        executionRecord.markRunning();
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 2 failed")
        );
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(executionRecord.task().attempt()).isEqualTo(3);

        // Attempt 3
        ownership.attach(executionRecord);
        executionRecord.markRunning();
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 3 failed")
        );
        assertThat(executionRecord.completion().isDone()).isFalse();
        assertThat(executionRecord.task().attempt()).isEqualTo(4);

        // Attempt 4 (last one, maxRetries reached)
        ownership.attach(executionRecord);
        executionRecord.markRunning();
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Attempt 4 failed")
        );

        // NOW the future should be completed with the error
        assertThat(executionRecord.completion().isDone()).isTrue();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(ownership.releases("testFunc")).isEqualTo(4);
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

        ExecutionRecord executionRecord = executionStore.get(response.executionId()).orElseThrow();
        ownership.attach(executionRecord);
        executionRecord.markRunning();

        // Complete with success
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.success("result")
        );

        // Future should be completed immediately
        assertThat(executionRecord.completion().isDone()).isTrue();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(executionRecord.output()).isEqualTo("result");
        assertThat(ownership.releases("testFunc")).isEqualTo(1);
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
        ExecutionRecord executionRecord = executionStore.get(originalExecutionId).orElseThrow();

        // Trigger a retry
        invocationService.completeExecution(
                originalExecutionId,
                InvocationResult.error("ERROR", "Failed")
        );

        // ExecutionId should be the same
        assertThat(executionRecord.executionId()).isEqualTo(originalExecutionId);
        assertThat(executionRecord.task().executionId()).isEqualTo(originalExecutionId);
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

        ExecutionRecord executionRecord = executionStore.get(response.executionId()).orElseThrow();

        // Original task has idempotency key
        assertThat(executionRecord.task().idempotencyKey()).isEqualTo("my-idempotency-key");

        // Trigger a retry
        invocationService.completeExecution(
                response.executionId(),
                InvocationResult.error("ERROR", "Failed")
        );

        // Retry task should NOT have idempotency key (internal retry)
        assertThat(executionRecord.task().idempotencyKey()).isNull();
    }

    @Test
    void invokeAsync_whenEnqueuerDisabled_throwsAsyncQueueUnavailableException() {
        when(enqueuer.supportsAsync()).thenReturn(false);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.DIRECT);

        InvocationRequest request = new InvocationRequest("payload", null);
        assertThatThrownBy(() -> invocationService.invokeAsync(
                "testFunc",
                request,
                null,
                null
        )).isInstanceOf(AsyncQueueUnavailableException.class)
                .hasMessage("Async invocation requires the async-queue module");
    }

    @Test
    void invokeAsync_whenQueueUnavailable_doesNotLeakExecutionOrIdempotencyEntry() {
        when(enqueuer.supportsAsync()).thenReturn(false, true, true);
        when(enqueuer.enqueue(any())).thenReturn(true);

        InvocationRequest request = new InvocationRequest("payload", null);
        assertThatThrownBy(() -> invocationService.invokeAsync(
                "testFunc",
                request,
                "idem-123",
                null
        )).isInstanceOf(AsyncQueueUnavailableException.class);

        InvocationResponse queued = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                "idem-123",
                null
        );

        InvocationResponse replay = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                "idem-123",
                null
        );

        assertThat(replay.executionId()).isEqualTo(queued.executionId());
        verify(enqueuer, times(1)).enqueue(any());
    }

    @Test
    void invokeAsync_existingExecution_returnsQueuedWithoutReenqueue() {
        InvocationResponse first = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                "idem-replay",
                null
        );

        InvocationResponse replay = invocationService.invokeAsync(
                "testFunc",
                new InvocationRequest("payload", null),
                "idem-replay",
                null
        );

        assertThat(replay.executionId()).isEqualTo(first.executionId());
        assertThat(replay.status()).isEqualTo("queued");
        verify(enqueuer, times(1)).enqueue(any());
    }

}
