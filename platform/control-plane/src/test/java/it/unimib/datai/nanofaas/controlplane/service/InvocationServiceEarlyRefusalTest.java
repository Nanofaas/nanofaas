package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Under overload most arrivals are refused, so what a refusal costs is what the
 * platform mostly spends. Building an execution, storing it and claiming an
 * idempotency key only to undo all three is the work this avoids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceEarlyRefusalTest {

    @Mock private FunctionService functionService;
    @Mock private InvocationEnqueuer enqueuer;
    @Mock private Metrics metrics;
    @Mock private DispatcherRouter dispatcherRouter;
    @Mock private SyncQueueGateway syncQueueGateway;

    private ExecutionStore executionStore;
    private IdempotencyStore idempotencyStore;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        when(syncQueueGateway.enabled()).thenReturn(false);
        invocationService = TestWaiterCapacity.service(
                functionService, enqueuer, executionStore, idempotencyStore,
                metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, enqueuer::enqueue, dispatcherRouter, metrics),
                "full-fn", "hot-fn", "replay-fn", "sync-fn");
    }

    @Test
    void aFullQueueRefusesBeforeAnExecutionIsBuilt() {
        FunctionSpec spec = spec("full-fn");
        when(functionService.get("full-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.isQueueFull("full-fn")).thenReturn(true);

        InvocationRequest request = new InvocationRequest("payload", Map.of());
        var refused = invocationService.invokeSyncReactive(
                "full-fn", request, null, null, 1_000);
        assertThatThrownBy(refused::block)
                .isInstanceOf(QueueFullException.class);

        // The old path reached enqueue, which refused; nothing calls it now.
        verify(enqueuer, never()).enqueue(any());
        verify(metrics).queueRejected("full-fn");
        assertThat(executionStore.size()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void absentIdempotencyKeysUseTheSameEarlyRefusal(String idempotencyKey) {
        FunctionSpec spec = spec("full-fn");
        when(functionService.get("full-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.isQueueFull("full-fn")).thenReturn(true);

        InvocationRequest request = new InvocationRequest("payload", Map.of());
        var refused = invocationService.invokeSyncReactive(
                "full-fn", request, idempotencyKey, null, 1_000);
        assertThatThrownBy(refused::block)
                .isInstanceOf(QueueFullException.class);

        verify(enqueuer, never()).enqueue(any());
        assertThat(executionStore.size()).isZero();
    }

    @Test
    void anIdempotentRequestStillReachesTheStoreWhenTheQueueIsFull() {
        FunctionSpec spec = spec("replay-fn");
        when(functionService.get("replay-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.isQueueFull(anyString())).thenReturn(true);
        InvocationTask task = new InvocationTask(
                "exec-1", "replay-fn", spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1,
                InvocationKind.SYNC
            );
        ExecutionRecord execution = new ExecutionRecord("exec-1", task);
        execution.markSuccess("stored-ok");
        executionStore.put(execution);
        idempotencyStore.put("replay-fn", "idem-1", "exec-1");

        // A replay's answer is already computed: refusing it because the queue is full
        // would deny a caller a result the platform is holding.
        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-fn", new InvocationRequest("payload", Map.of()), "idem-1", null, 1_000)
                .block().response();

        assertThat(response.output()).isEqualTo("stored-ok");
    }

    @Test
    void theSyncQueueKeepsOwningAdmissionWhenItIsLoaded() {
        FunctionSpec spec = spec("sync-fn");
        when(functionService.get("sync-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        when(enqueuer.isQueueFull(anyString())).thenReturn(true);

        // The async queue being full says nothing about a path that never uses it.
        invocationService.invokeSyncReactive(
                "sync-fn", new InvocationRequest("payload", Map.of()), null, null, 50)
                .onErrorResume(e -> reactor.core.publisher.Mono.empty()).block();

        verify(syncQueueGateway).enqueueOrThrow(any());
    }

    @Test
    void aRefusalIsDecidedOnTheCallingThread() {
        FunctionSpec spec = spec("hot-fn");
        AtomicReference<String> preparedOn = new AtomicReference<>();
        when(functionService.get("hot-fn")).thenAnswer(call -> {
            preparedOn.set(Thread.currentThread().getName());
            return Optional.of(spec);
        });
        when(enqueuer.isQueueFull("hot-fn")).thenReturn(true);
        String caller = Thread.currentThread().getName();

        InvocationRequest request = new InvocationRequest("payload", Map.of());
        var refused = invocationService.invokeSyncReactive(
                "hot-fn", request, null, null, 1_000);
        assertThatThrownBy(refused::block)
                .isInstanceOf(QueueFullException.class);

        // Without an idempotency key nothing on this path can park, so nothing needs
        // a second thread. In production the caller is a Netty event loop, and the
        // handoff cost 6.1us per request against 0.085us of work.
        assertThat(preparedOn.get()).isEqualTo(caller);
    }

    @Test
    void anIdempotencyKeyStillMovesPreparationOffTheCallingThread() {
        FunctionSpec spec = spec("replay-fn");
        AtomicReference<String> preparedOn = new AtomicReference<>();
        when(functionService.get("replay-fn")).thenAnswer(call -> {
            preparedOn.set(Thread.currentThread().getName());
            return Optional.of(spec);
        });
        InvocationTask task = new InvocationTask(
                "exec-1", "replay-fn", spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1,
                InvocationKind.SYNC
            );
        ExecutionRecord execution = new ExecutionRecord("exec-1", task);
        execution.markSuccess("stored-ok");
        executionStore.put(execution);
        idempotencyStore.put("replay-fn", "idem-1", "exec-1");
        String caller = Thread.currentThread().getName();

        invocationService.invokeSyncReactive(
                "replay-fn", new InvocationRequest("payload", Map.of()), "idem-1", null, 1_000).block();

        // This is the branch that can spin on a contended claim, so it keeps the hop.
        assertThat(preparedOn.get()).isNotEqualTo(caller).startsWith("boundedElastic");
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "image", null, Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
    }
}
