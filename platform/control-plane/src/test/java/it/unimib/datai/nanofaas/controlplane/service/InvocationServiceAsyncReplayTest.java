package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * Regression coverage for A1: {@code invokeAsync} must handle a settled/archived
 * outcome the same way {@link ReactiveInvocationCoordinator#invoke} already does
 * for the sync path - by consulting {@code lookup.settledOutcome()} before ever
 * touching {@code lookup.executionRecord()}, which the factory returns as
 * {@code null} once the key points at an archived execution.
 *
 * <p>Before the fix, a second {@code :enqueue} with the same idempotency key,
 * issued after the first execution settled (moved from the live store into the
 * outcome archive) and before the key expires, threw a NullPointerException from
 * {@code InvocationResponseMapper.terminalResponse(ExecutionRecord)} dereferencing
 * a null record.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceAsyncReplayTest {

    @Mock
    private FunctionService functionService;

    @Mock
    private InvocationEnqueuer enqueuer;

    /**
     * A spy over a real Metrics, not a bare mock: the store's terminal listener records the
     * invocation's end-to-end conclusion through timers(), and a mock returning null there
     * would fail for a reason that has nothing to do with what these tests assert.
     */
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
        metrics = spy(new Metrics(new SimpleMeterRegistry()));

        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
                executionStore, (queued, due, rejected) -> enqueuer.enqueue(queued), dispatcherRouter, metrics);

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
                3,
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
    }

    /** Queues the first execution and drives its record straight to SUCCESS/settled, bypassing retry logic. */
    private ExecutionRecord queueAndSettleSuccess(String idempotencyKey, Object output) {
        InvocationResponse queued = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), idempotencyKey, null);
        ExecutionRecord executionRecord = executionStore.get(queued.executionId()).orElseThrow();
        executionRecord.markSuccess(output, 200, null, null);
        executionStore.settle(executionRecord);
        return executionRecord;
    }

    private ExecutionRecord queueAndSettleError(String idempotencyKey, ErrorInfo error) {
        InvocationResponse queued = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), idempotencyKey, null);
        ExecutionRecord executionRecord = executionStore.get(queued.executionId()).orElseThrow();
        executionRecord.markError(error);
        executionStore.settle(executionRecord);
        return executionRecord;
    }

    private ExecutionRecord queueAndSettleTimeout(String idempotencyKey) {
        InvocationResponse queued = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), idempotencyKey, null);
        ExecutionRecord executionRecord = executionStore.get(queued.executionId()).orElseThrow();
        executionRecord.markTimeout();
        executionStore.settle(executionRecord);
        return executionRecord;
    }

    @Test
    void invokeAsync_replaysArchivedSuccess_withoutNpe() {
        ExecutionRecord settled = queueAndSettleSuccess("idem-success", "the-result");
        // The record is gone from the live store; only the outcome remains.
        assertThat(executionStore.getOrNull(settled.executionId())).isNull();
        assertThat(executionStore.outcomeOf(settled.executionId())).isNotNull();

        InvocationResponse replay = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-success", null);

        assertThat(replay.executionId()).isEqualTo(settled.executionId());
        assertThat(replay.status()).isEqualTo("success");
        assertThat(replay.output()).isEqualTo("the-result");

        // No second execution, no second dispatch/enqueue triggered by the replay.
        verify(enqueuer, times(1)).enqueue(any());
    }

    @Test
    void invokeAsync_replaysArchivedError_withoutNpe() {
        ErrorInfo error = new ErrorInfo("BOOM", "it broke");
        ExecutionRecord settled = queueAndSettleError("idem-error", error);
        assertThat(executionStore.getOrNull(settled.executionId())).isNull();

        InvocationResponse replay = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-error", null);

        assertThat(replay.executionId()).isEqualTo(settled.executionId());
        assertThat(replay.status()).isEqualTo("error");
        assertThat(replay.error()).isEqualTo(error);
        verify(enqueuer, times(1)).enqueue(any());
    }

    @Test
    void invokeAsync_replaysArchivedTimeout_withoutNpe() {
        ExecutionRecord settled = queueAndSettleTimeout("idem-timeout");
        assertThat(executionStore.getOrNull(settled.executionId())).isNull();

        InvocationResponse replay = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-timeout", null);

        assertThat(replay.executionId()).isEqualTo(settled.executionId());
        assertThat(replay.status()).isEqualTo("timeout");
        verify(enqueuer, times(1)).enqueue(any());
    }

    @Test
    void invokeAsync_replayOfArchivedOutcome_doesNotIncreaseAdmissionCounter() {
        queueAndSettleSuccess("idem-admission", "ok");

        invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-admission", null);

        // Admission (metrics.admitted) fires only for the original enqueue, never for the replay.
        verify(metrics, times(1)).admitted(anyString(), any());
        verify(enqueuer, times(1)).enqueue(any());
        verify(dispatcherRouter, never()).dispatchLocal(any());
    }

    @Test
    void invokeAsync_concurrentReplayRacingLiveToSettledTransition_doesNotDuplicateExecution() {
        // First arrival: claims the key and queues a live execution.
        InvocationResponse first = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-race", null);
        ExecutionRecord executionRecord = executionStore.get(first.executionId()).orElseThrow();

        // A second arrival while the record is still live (not yet settled) must
        // find the same live record and must not enqueue again.
        InvocationResponse duringLive = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-race", null);
        assertThat(duringLive.executionId()).isEqualTo(first.executionId());
        assertThat(duringLive.status()).isEqualTo("queued");

        // The transition to settled now happens...
        executionRecord.markSuccess("done", 200, null, null);
        executionStore.settle(executionRecord);

        // ...and a third arrival, after settlement, must replay the archived
        // outcome rather than create a second execution.
        InvocationResponse afterSettle = invocationService.invokeAsync(
                "testFunc", new InvocationRequest("payload", null), "idem-race", null);
        assertThat(afterSettle.executionId()).isEqualTo(first.executionId());
        assertThat(afterSettle.status()).isEqualTo("success");
        assertThat(afterSettle.output()).isEqualTo("done");

        // Exactly one enqueue across all three calls: only the original admission.
        verify(enqueuer, times(1)).enqueue(any());
        verify(metrics, times(1)).admitted(anyString(), any());
    }

    @Test
    void invokeAsync_replayOfEvictedOutcome_throwsOutcomeGoneInsteadOfReEnqueueing() {
        // One outcome slot: the second execution evicts the first outcome for capacity,
        // before its retention window ends. The key binding must still hold, so the
        // replay must NOT re-run the function.
        // A byte budget too small for any payload: the binding outlives the outcome, which
        // is the scenario. "One slot" made this depend on which entry Caffeine evicts.
        executionStore = new ExecutionStore(
                new ExecutionStoreProperties(Duration.ofMinutes(5), Duration.ofMinutes(30),
                        Duration.ofSeconds(30), 1, 100_000, 1),
                new SimpleMeterRegistry());
        idempotencyStore = new IdempotencyStore();
        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
                executionStore, (queued, due, rejected) -> enqueuer.enqueue(queued), dispatcherRouter, metrics);
        invocationService = TestWaiterCapacity.service(
                functionService, enqueuer, executionStore, idempotencyStore, metrics, syncQueueGateway,
                completionHandler, "testFunc");

        ExecutionRecord first = queueAndSettleSuccess("idem-gone", "first");
        assertThat(executionStore.size()).isZero();
        assertThat(executionStore.outcomeOf(first.executionId())).isNull();

        var request = new InvocationRequest("payload", null);
        assertThatThrownBy(() -> invocationService.invokeAsync("testFunc", request, "idem-gone", null))
                .isInstanceOf(OutcomeGoneException.class)
                .hasMessageContaining(first.executionId());

        // Exactly one admission (the original); the replay enqueued nothing. This is the
        // assertion the whole test exists for: a replay past eviction must never re-run.
        verify(enqueuer, times(1)).enqueue(any());
    }
}
