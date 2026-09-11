package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceDispatchTest {
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
    private ExecutionCompletionHandler completionHandler;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();

        completionHandler = new ExecutionCompletionHandler(executionStore, enqueuer::enqueue, dispatcherRouter, metrics);

        invocationService = TestWaiterCapacity.service(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                metrics,
                syncQueueGateway,
                completionHandler,
                "replay-success-fn", "replay-reactive-success-fn", "replay-sync-timeout-fn",
                "replay-timeout-fn", "fn", "inline-fn", "queued-sync-fn", "sync-queued-fn",
                "sync-queued-sync-fn", "idem-admission-race-fn", "sync-reject-fn",
                "sync-reject-local-fn", "reactive-sync-reject-fn", "queue-reject-fn", "queue-timeout-fn",
                "timeout-fn", "timeout-reactive-fn"
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
        when(metrics.timers(anyString())).thenAnswer(invocation -> new Metrics.FunctionTimers(
                metrics.latency(invocation.getArgument(0)),
                metrics.initDuration(invocation.getArgument(0)),
                metrics.queueWait(invocation.getArgument(0)),
                metrics.e2eLatency(invocation.getArgument(0))
        ));
    }

    @Test
    void invokeSync_existingSuccessfulExecution_returnsMappedResponseWithoutDispatch() {
        FunctionSpec spec = functionSpec("replay-success-fn", ExecutionMode.LOCAL);
        when(functionService.get("replay-success-fn")).thenReturn(Optional.of(spec));

        InvocationTask task = task("exec-replay-success", "replay-success-fn", ExecutionMode.LOCAL);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        executionRecord.markSuccess("replayed-ok");
        executionStore.put(executionRecord);
        idempotencyStore.put("replay-success-fn", "idem-1", executionRecord.executionId());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-success-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-1",
                "trace-1",
                1_000
        ).block().response();

        assertThat(response.executionId()).isEqualTo("exec-replay-success");
        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("replayed-ok");
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSyncReactive_existingSuccessfulExecution_returnsMappedResponseWithoutDispatch() {
        FunctionSpec spec = functionSpec("replay-reactive-success-fn", ExecutionMode.LOCAL);
        when(functionService.get("replay-reactive-success-fn")).thenReturn(Optional.of(spec));

        InvocationTask task = task("exec-reactive-replay-success", "replay-reactive-success-fn", ExecutionMode.LOCAL);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        executionRecord.markSuccess("reactive-replayed-ok");
        executionStore.put(executionRecord);
        idempotencyStore.put("replay-reactive-success-fn", "idem-reactive-success", executionRecord.executionId());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-reactive-success-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-reactive-success",
                "trace-1",
                1_000
        ).block().response();

        assertThat(response).isNotNull();
        assertThat(response.executionId()).isEqualTo("exec-reactive-replay-success");
        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("reactive-replayed-ok");
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSync_existingTimeoutExecution_returnsTimeoutWithoutRedispatch() {
        FunctionSpec spec = functionSpec("replay-sync-timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("replay-sync-timeout-fn")).thenReturn(Optional.of(spec));

        InvocationTask task = task("exec-sync-replay-timeout", "replay-sync-timeout-fn", ExecutionMode.LOCAL);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        executionRecord.markTimeout();
        executionStore.put(executionRecord);
        idempotencyStore.put("replay-sync-timeout-fn", "idem-sync-timeout", executionRecord.executionId());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-sync-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-sync-timeout",
                "trace-1",
                1_000
        ).block().response();

        assertThat(response.executionId()).isEqualTo("exec-sync-replay-timeout");
        assertThat(response.status()).isEqualTo("timeout");
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSyncReactive_existingTimeoutExecution_returnsTimeoutWithoutRedispatch() {
        FunctionSpec spec = functionSpec("replay-timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("replay-timeout-fn")).thenReturn(Optional.of(spec));

        InvocationTask task = task("exec-replay-timeout", "replay-timeout-fn", ExecutionMode.LOCAL);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        executionRecord.markTimeout();
        executionStore.put(executionRecord);
        idempotencyStore.put("replay-timeout-fn", "idem-timeout", executionRecord.executionId());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout",
                "trace-1",
                1_000
        ).block().response();

        assertThat(response).isNotNull();
        assertThat(response.executionId()).isEqualTo("exec-replay-timeout");
        assertThat(response.status()).isEqualTo("timeout");
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void dispatch_whenExecutionRecordMissing_releasesSlotAndSkipsRouter() {
        InvocationTask missingTask = new InvocationTask(
                "missing-exec",
                "fn",
                functionSpec("fn", ExecutionMode.LOCAL),
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );

        completionHandler.dispatch(ownership.acquire(missingTask));

        assertThat(ownership.releases("fn")).isEqualTo(1);
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void dispatch_whenRouterThrowsSynchronously_completesExecutionWithError() throws Exception {
        InvocationTask task = task("exec-1", "local-fn", ExecutionMode.LOCAL);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        executionStore.put(executionRecord);

        when(dispatcherRouter.dispatchLocal(any())).thenThrow(new RuntimeException("router down"));

        assertThatCode(() -> completionHandler.dispatch(ownership.acquire(task))).doesNotThrowAnyException();

        InvocationResult result = executionRecord.completion().get(1, TimeUnit.SECONDS);
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("LOCAL_ERROR");
        assertThat(result.error().message()).contains("router down");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(ownership.releases("local-fn")).isEqualTo(1);
    }

    @Test
    void dispatch_poolMode_routesToPoolDispatcherAndCompletesSuccess() throws Exception {
        InvocationTask task = task("exec-2", "pool-fn", ExecutionMode.EXTERNAL);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);
        executionStore.put(executionRecord);

        when(dispatcherRouter.dispatchExternal(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok"))));

        completionHandler.dispatch(ownership.acquire(task));

        InvocationResult result = executionRecord.completion().get(1, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("ok");
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.SUCCESS);
        verify(dispatcherRouter).dispatchExternal(ownership.acquire(task));
    }

    @Test
    void invokeSync_whenSyncQueueAndEnqueuerDisabled_dispatchesInline() {
        FunctionSpec spec = functionSpec("inline-fn", ExecutionMode.LOCAL);
        when(functionService.get("inline-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(false);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.DIRECT);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("inline-ok"))));

        InvocationResponse response = invocationService.invokeSyncReactive(
                "inline-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ).block().response();

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("inline-ok");
        verify(dispatcherRouter).dispatchLocal(any());
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        // Direct admission releases its own capacity lease, never a name-based queue slot.
        assertThat(ownership.releases()).isZero();
    }

    @Test
    void invokeSync_whenSyncQueueGatewayMissingAndEnqueuerDisabled_dispatchesInline() {
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(executionStore, enqueuer::enqueue, dispatcherRouter, metrics);
        InvocationService invocationServiceWithoutSyncQueue = TestWaiterCapacity.service(
                functionService,
                enqueuer,
                executionStore,
                new IdempotencyStore(),
                metrics,
                null,
                handler,
                "inline-no-sync-queue-fn"
        );

        FunctionSpec spec = functionSpec("inline-no-sync-queue-fn", ExecutionMode.LOCAL);
        when(functionService.get("inline-no-sync-queue-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.supportsAsync()).thenReturn(false);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.DIRECT);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("inline-ok"))));

        InvocationResponse response = invocationServiceWithoutSyncQueue.invokeSyncReactive(
                "inline-no-sync-queue-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ).block().response();

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("inline-ok");
        verify(dispatcherRouter).dispatchLocal(any());
        verify(enqueuer, never()).enqueue(any());
        // Direct admission releases its own capacity lease, never a name-based queue slot.
        assertThat(ownership.releases()).isZero();
    }

    @Test
    void invokeSync_whenSyncQueueDisabledAndEnqueuerEnabled_enqueuesAndWaitsForCompletion() {
        FunctionSpec spec = functionSpec("queued-sync-fn", ExecutionMode.LOCAL);
        when(functionService.get("queued-sync-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            ExecutionRecord record = executionStore.getOrNull(task.executionId());
            if (record != null) {
                ownership.attach(record);
        record.markRunning();
            }
            completionHandler.completeExecution(
                    task.executionId(),
                    DispatchResult.warm(InvocationResult.success("queued-ok"))
            );
            return true;
        }).when(enqueuer).enqueue(any());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "queued-sync-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ).block().response();

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("queued-ok");
        verify(enqueuer).enqueue(any());
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(dispatcherRouter, never()).dispatchLocal(any());
        assertThat(ownership.releases("queued-sync-fn")).isEqualTo(1);
    }

    @Test
    void invokeSyncReactive_whenSyncQueueEnabled_usesSyncQueueOnly() {
        FunctionSpec spec = functionSpec("sync-queued-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-queued-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);

        // Admission is lazy and runs on boundedElastic: it happens on subscription,
        // so subscribe and verify with a timeout instead of expecting eager side effects.
        invocationService.invokeSyncReactive(
                "sync-queued-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ).subscribe();

        verify(syncQueueGateway, org.mockito.Mockito.timeout(2_000)).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSync_whenSyncQueueEnabled_usesSyncQueueOnlyAndReturnsSuccess() {
        FunctionSpec spec = functionSpec("sync-queued-sync-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-queued-sync-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            ExecutionRecord record = executionStore.getOrNull(task.executionId());
            if (record != null) {
                ownership.attach(record);
        record.markRunning();
            }
            completionHandler.completeExecution(
                    task.executionId(),
                    DispatchResult.warm(InvocationResult.success("ok"))
            );
            return null;
        }).when(syncQueueGateway).enqueueOrThrow(any());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "sync-queued-sync-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ).block().response();

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("ok");
        verify(syncQueueGateway).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        assertThat(ownership.releases("sync-queued-sync-fn")).isEqualTo(1);
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSyncReactive_whenSyncQueueRejects_emitsReactiveError() {
        FunctionSpec spec = functionSpec("sync-reject-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-reject-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        doThrow(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 3))
                .when(syncQueueGateway).enqueueOrThrow(any());

        AtomicReference<reactor.core.publisher.Mono<SyncInvocation>> monoRef = new AtomicReference<>();
        assertThatCode(() -> monoRef.set(invocationService.invokeSyncReactive(
                "sync-reject-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ))).doesNotThrowAnyException();

        reactor.core.publisher.Mono<SyncInvocation> rejectedMono = monoRef.get();
        assertThatThrownBy(rejectedMono::block)
                .isInstanceOf(SyncQueueRejectedException.class);
    }

    @Test
    void invokeSync_andReactiveQueueTimeoutSurfaceTheSameContract() {
        FunctionSpec spec = functionSpec("queue-timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("queue-timeout-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        when(syncQueueGateway.retryAfterSeconds()).thenReturn(9);
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            completionHandler.completeExecution(
                    task.executionId(),
                    DispatchResult.warm(InvocationResult.error("QUEUE_TIMEOUT", "queue wait exceeded"))
            );
            return null;
        }).when(syncQueueGateway).enqueueOrThrow(any());

        assertThatThrownBy(() -> invocationService.invokeSyncReactive(
                "queue-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-sync-timeout",
                null,
                1_000
        ).block()).isInstanceOfSatisfying(SyncQueueRejectedException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(SyncQueueRejectReason.TIMEOUT);
            assertThat(ex.retryAfterSeconds()).isEqualTo(9);
        });

        assertThatThrownBy(() -> invocationService.invokeSyncReactive(
                "queue-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-reactive-timeout",
                null,
                1_000
        ).block()).isInstanceOfSatisfying(SyncQueueRejectedException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(SyncQueueRejectReason.TIMEOUT);
            assertThat(ex.retryAfterSeconds()).isEqualTo(9);
        });
    }

    @Test
    void invokeSync_aWaiterTimeoutThenLateSuccess_leavesTheSuccessForTheReplay() {
        CompletableFuture<DispatchResult> dispatchFuture = new CompletableFuture<>();
        FunctionSpec spec = functionSpec("timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("timeout-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(false);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.DIRECT);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(dispatchFuture);

        // A waiter's own budget runs out first (per-waiter timeout, invariant I1): it
        // receives the timeout response, but the shared execution keeps running.
        InvocationResponse first = invocationService.invokeSyncReactive(
                "timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout",
                null,
                10
        ).block().response();

        assertThat(first.status()).isEqualTo("timeout");

        dispatchFuture.complete(DispatchResult.warm(InvocationResult.success("late-ok")));

        // The backend answer then arrives and is the shared terminal result: the replay
        // of the same key observes success, not the short waiter's timeout.
        InvocationResponse second = invocationService.invokeSyncReactive(
                "timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout",
                null,
                10
        ).block().response();

        assertThat(second.status()).isEqualTo("success");
        assertThat(invocationService.getStatus(first.executionId())).get()
                .extracting(ExecutionStatus::status)
                .isEqualTo("success");
    }

    @Test
    void invokeSyncReactive_aWaiterTimeoutThenLateSuccess_leavesTheSuccessForTheReplay() {
        CompletableFuture<DispatchResult> dispatchFuture = new CompletableFuture<>();
        FunctionSpec spec = functionSpec("timeout-reactive-fn", ExecutionMode.LOCAL);
        when(functionService.get("timeout-reactive-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(false);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.DIRECT);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(dispatchFuture);

        InvocationResponse first = invocationService.invokeSyncReactive(
                "timeout-reactive-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout-reactive",
                null,
                10
        ).block().response();

        assertThat(first).isNotNull();
        assertThat(first.status()).isEqualTo("timeout");

        dispatchFuture.complete(DispatchResult.warm(InvocationResult.success("late-ok")));

        InvocationResponse second = invocationService.invokeSyncReactive(
                "timeout-reactive-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout-reactive",
                null,
                10
        ).block().response();

        assertThat(second).isNotNull();
        assertThat(second.status()).isEqualTo("success");
        assertThat(invocationService.getStatus(first.executionId())).get()
                .extracting(ExecutionStatus::status)
                .isEqualTo("success");
    }

    @Test
    void invokeAsync_staleIdempotencyMapping_createsOnlyOneFreshExecutionUnderContention() throws Exception {
        FunctionSpec spec = functionSpec("stale-idem-fn", ExecutionMode.LOCAL);
        when(functionService.get("stale-idem-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        when(enqueuer.enqueue(any())).thenReturn(true);

        IdempotencyStore staleStore = new IdempotencyStore(Duration.ofMinutes(15));
        staleStore.put("stale-idem-fn", "same-key", "evicted-execution");
        // The stale mapping is explicitly abandoned after publication: that is what makes
        // it reclaimable, not the mere absence of the execution it points at (finding R2).
        staleStore.markReclaimable("stale-idem-fn", "same-key", "evicted-execution");
        InvocationService racingService = TestWaiterCapacity.service(
                functionService,
                enqueuer,
                executionStore,
                staleStore,
                metrics,
                syncQueueGateway,
                completionHandler,
                "stale-idem-fn"
        );

        int contenders = 2;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ArrayList<Future<InvocationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return racingService.invokeAsync(
                            "stale-idem-fn",
                            new InvocationRequest("payload", Map.of()),
                            "same-key",
                            null
                    );
                }));
            }

            start.countDown();

            ArrayList<String> executionIds = new ArrayList<>();
            for (Future<InvocationResponse> future : futures) {
                executionIds.add(future.get().executionId());
            }

            assertThat(new HashSet<>(executionIds)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invokeAsync_staleIdempotencyMapping_doesNotAllowReplacementBeforeWinnerIsPublished() throws Exception {
        FunctionSpec spec = functionSpec("stale-publication-fn", ExecutionMode.LOCAL);
        when(functionService.get("stale-publication-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        when(enqueuer.enqueue(any())).thenReturn(true);

        BlockingExecutionStore blockedStore = new BlockingExecutionStore();
        IdempotencyStore staleStore = new IdempotencyStore(Duration.ofMinutes(15));
        staleStore.put("stale-publication-fn", "same-key", "evicted-execution");
        // The stale mapping is explicitly abandoned after publication: that is what makes
        // it reclaimable, not the mere absence of the execution it points at (finding R2).
        staleStore.markReclaimable("stale-publication-fn", "same-key", "evicted-execution");
        InvocationService racingService = TestWaiterCapacity.service(
                functionService,
                enqueuer,
                blockedStore,
                staleStore,
                metrics,
                syncQueueGateway,
                new ExecutionCompletionHandler(blockedStore, enqueuer::enqueue, dispatcherRouter, metrics),
                "stale-publication-fn"
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InvocationResponse> first = executor.submit(() -> racingService.invokeAsync(
                    "stale-publication-fn",
                    new InvocationRequest("payload", Map.of()),
                    "same-key",
                    null
            ));

            blockedStore.awaitFirstPutStarted();

            Future<InvocationResponse> second = executor.submit(() -> racingService.invokeAsync(
                    "stale-publication-fn",
                    new InvocationRequest("payload", Map.of()),
                    "same-key",
                    null
            ));

            blockedStore.waitForConcurrentLookupWindow();
            blockedStore.allowFirstPutToComplete();

            assertThat(new HashSet<>(List.of(
                    first.get().executionId(),
                    second.get().executionId()
            ))).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invokeAsync_whenEnqueueRejects_removesCreatedExecutionRecord() {
        FunctionSpec spec = functionSpec("queue-reject-fn", ExecutionMode.LOCAL);
        when(functionService.get("queue-reject-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        AtomicReference<String> rejectedExecutionId = new AtomicReference<>();
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            rejectedExecutionId.set(task.executionId());
            return false;
        }).when(enqueuer).enqueue(any());

        InvocationRequest request = new InvocationRequest("payload", Map.of());
        assertThatThrownBy(() -> invocationService.invokeAsync(
                "queue-reject-fn",
                request,
                null,
                null
        )).isInstanceOf(QueueFullException.class);

        assertThat(rejectedExecutionId).hasValueSatisfying(executionId ->
                assertThat(executionStore.get(executionId)).isEmpty());
    }

    @Test
    void invokeSync_whenEnqueuerRejects_removesCreatedExecutionRecord() {
        FunctionSpec spec = functionSpec("sync-reject-local-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-reject-local-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        AtomicReference<String> rejectedExecutionId = new AtomicReference<>();
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            rejectedExecutionId.set(task.executionId());
            return false;
        }).when(enqueuer).enqueue(any());

        InvocationRequest request = new InvocationRequest("payload", Map.of());
        assertThatThrownBy(() -> invokeSyncReactiveBlocking(invocationService, "sync-reject-local-fn", request, 1_000))
                .isInstanceOf(QueueFullException.class);

        assertThat(rejectedExecutionId).hasValueSatisfying(executionId ->
                assertThat(executionStore.get(executionId)).isEmpty());
    }

    @Test
    void invokeSyncReactive_whenSyncQueueRejects_removesCreatedExecutionRecord() {
        FunctionSpec spec = functionSpec("reactive-sync-reject-fn", ExecutionMode.LOCAL);
        when(functionService.get("reactive-sync-reject-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        AtomicReference<String> rejectedExecutionId = new AtomicReference<>();
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            rejectedExecutionId.set(task.executionId());
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 1);
        }).when(syncQueueGateway).enqueueOrThrow(any());

        InvocationRequest request = new InvocationRequest("payload", Map.of());
        assertThatThrownBy(() -> invokeSyncReactiveBlocking(invocationService, "reactive-sync-reject-fn", request, 1_000))
                .isInstanceOf(SyncQueueRejectedException.class);

        assertThat(rejectedExecutionId).hasValueSatisfying(executionId ->
                assertThat(executionStore.get(executionId)).isEmpty());
    }

    @Test
    void invokeAsync_sameIdempotencyKeyWaitsForRejectedAdmissionBeforeCreatingReplacement() throws Exception {
        FunctionSpec spec = functionSpec("idem-admission-race-fn", ExecutionMode.LOCAL);
        when(functionService.get("idem-admission-race-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.supportsAsync()).thenReturn(true);
        org.mockito.Mockito.lenient().when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        CountDownLatch firstEnqueueStarted = new CountDownLatch(1);
        CountDownLatch allowFirstRejection = new CountDownLatch(1);
        AtomicReference<String> rejectedExecutionId = new AtomicReference<>();
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            if (rejectedExecutionId.compareAndSet(null, task.executionId())) {
                firstEnqueueStarted.countDown();
                assertThat(allowFirstRejection.await(5, TimeUnit.SECONDS)).isTrue();
                return false;
            }
            return true;
        }).when(enqueuer).enqueue(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InvocationResponse> first = executor.submit(() -> invocationService.invokeAsync(
                    "idem-admission-race-fn",
                    new InvocationRequest("payload", Map.of()),
                    "same-admission-key",
                    null
            ));
            assertThat(firstEnqueueStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<InvocationResponse> second = executor.submit(() -> invocationService.invokeAsync(
                    "idem-admission-race-fn",
                    new InvocationRequest("payload", Map.of()),
                    "same-admission-key",
                    null
            ));

            // the second invocation must still be waiting on the pending admission
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(second).isNotDone());

            allowFirstRejection.countDown();

            assertThatThrownBy(() -> first.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(QueueFullException.class);
            InvocationResponse replacement = second.get(1, TimeUnit.SECONDS);
            assertThat(replacement.executionId()).isNotEqualTo(rejectedExecutionId.get());
            assertThat(executionStore.get(rejectedExecutionId.get())).isEmpty();
            assertThat(executionStore.get(replacement.executionId())).isPresent();
        } finally {
            allowFirstRejection.countDown();
            executor.shutdownNow();
        }
    }

    private static final class BlockingExecutionStore extends ExecutionStore {
        private final CountDownLatch firstPutStarted = new CountDownLatch(1);
        private final CountDownLatch hiddenExecutionLookup = new CountDownLatch(1);
        private final CountDownLatch allowFirstPutToComplete = new CountDownLatch(1);
        private final AtomicBoolean blockNextPut = new AtomicBoolean(true);
        private volatile String blockedExecutionId;

        @Override
        public void put(ExecutionRecord executionRecord) {
            if (blockNextPut.compareAndSet(true, false)) {
                blockedExecutionId = executionRecord.executionId();
                firstPutStarted.countDown();
                try {
                    allowFirstPutToComplete.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            super.put(executionRecord);
        }

        @Override
        public ExecutionRecord getOrNull(String executionId) {
            if (executionId != null && executionId.equals(blockedExecutionId)
                    && allowFirstPutToComplete.getCount() > 0) {
                hiddenExecutionLookup.countDown();
                return null;
            }
            return super.getOrNull(executionId);
        }

        void awaitFirstPutStarted() throws InterruptedException {
            assertThat(firstPutStarted.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void waitForConcurrentLookupWindow() throws InterruptedException {
            hiddenExecutionLookup.await(200, TimeUnit.MILLISECONDS);
        }

        void allowFirstPutToComplete() {
            allowFirstPutToComplete.countDown();
        }
    }

    private InvocationTask task(String executionId, String functionName, ExecutionMode mode) {
        FunctionSpec spec = functionSpec(functionName, mode);
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

    private FunctionSpec functionSpec(String functionName, ExecutionMode mode) {
        return new FunctionSpec(
                functionName,
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                1,
                null,
                mode,
                null,
                null,
                null
        );
    }

    private static void invokeSyncReactiveBlocking(InvocationService service, String functionName,
                                                   InvocationRequest request, int budgetMs) {
        service.invokeSyncReactive(functionName, request, null, null, budgetMs).block();
    }
}
