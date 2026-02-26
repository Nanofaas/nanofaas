package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
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
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    private ExecutionCompletionHandler completionHandler;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);

        completionHandler = new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics);

        invocationService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                rateLimiter,
                metrics,
                syncQueueGateway,
                completionHandler
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
        );

        invocationService.dispatch(missingTask);

        verify(enqueuer).releaseDispatchSlot("fn");
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void dispatch_whenRouterThrowsSynchronously_completesExecutionWithError() throws Exception {
        InvocationTask task = task("exec-1", "local-fn", ExecutionMode.LOCAL);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        executionStore.put(record);

        when(dispatcherRouter.dispatchLocal(any())).thenThrow(new RuntimeException("router down"));

        assertThatCode(() -> invocationService.dispatch(task)).doesNotThrowAnyException();

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

        invocationService.dispatch(task);

        InvocationResult result = record.completion().get(1, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("ok");
        assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
        verify(dispatcherRouter).dispatchPool(task);
    }

    @Test
    void invokeSync_whenSyncQueueAndEnqueuerDisabled_dispatchesInline() throws InterruptedException {
        FunctionSpec spec = functionSpec("inline-fn", ExecutionMode.LOCAL);
        when(functionService.get("inline-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.enabled()).thenReturn(false);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("inline-ok"))));

        InvocationResponse response = invocationService.invokeSync(
                "inline-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        );

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("inline-ok");
        verify(dispatcherRouter).dispatchLocal(any());
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verify(enqueuer).releaseDispatchSlot("inline-fn");
    }

    @Test
    void invokeSync_whenSyncQueueGatewayMissingAndEnqueuerDisabled_dispatchesInline() throws InterruptedException {
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics);
        InvocationService invocationServiceWithoutSyncQueue = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                new IdempotencyStore(),
                new RateLimiter(),
                metrics,
                null,
                handler
        );

        FunctionSpec spec = functionSpec("inline-no-sync-queue-fn", ExecutionMode.LOCAL);
        when(functionService.get("inline-no-sync-queue-fn")).thenReturn(Optional.of(spec));
        when(enqueuer.enabled()).thenReturn(false);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("inline-ok"))));

        InvocationResponse response = invocationServiceWithoutSyncQueue.invokeSync(
                "inline-no-sync-queue-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        );

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("inline-ok");
        verify(dispatcherRouter).dispatchLocal(any());
        verify(enqueuer, never()).enqueue(any());
        verify(enqueuer).releaseDispatchSlot("inline-no-sync-queue-fn");
    }

    @Test
    void invokeSync_whenSyncQueueDisabledAndEnqueuerEnabled_enqueuesAndWaitsForCompletion() throws Exception {
        FunctionSpec spec = functionSpec("queued-sync-fn", ExecutionMode.LOCAL);
        when(functionService.get("queued-sync-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            invocationService.completeExecution(
                    task.executionId(),
                    DispatchResult.warm(InvocationResult.success("queued-ok"))
            );
            return true;
        }).when(enqueuer).enqueue(any());

        InvocationResponse response = invocationService.invokeSync(
                "queued-sync-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        );

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("queued-ok");
        verify(enqueuer).enqueue(any());
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(dispatcherRouter, never()).dispatchLocal(any());
        verify(enqueuer).releaseDispatchSlot("queued-sync-fn");
    }

    @Test
    void invokeSyncReactive_whenSyncQueueEnabled_usesSyncQueueOnly() {
        FunctionSpec spec = functionSpec("sync-queued-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-queued-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);

        invocationService.invokeSyncReactive(
                "sync-queued-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        );

        verify(syncQueueGateway).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSync_whenSyncQueueEnabled_usesSyncQueueOnlyAndReturnsSuccess() throws InterruptedException {
        FunctionSpec spec = functionSpec("sync-queued-sync-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-queued-sync-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            invocationService.completeExecution(
                    task.executionId(),
                    DispatchResult.warm(InvocationResult.success("ok"))
            );
            return null;
        }).when(syncQueueGateway).enqueueOrThrow(any());

        InvocationResponse response = invocationService.invokeSync(
                "sync-queued-sync-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        );

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("ok");
        verify(syncQueueGateway).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verify(enqueuer).releaseDispatchSlot("sync-queued-sync-fn");
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSyncReactive_whenSyncQueueRejects_emitsReactiveError() {
        FunctionSpec spec = functionSpec("sync-reject-fn", ExecutionMode.LOCAL);
        when(functionService.get("sync-reject-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        doThrow(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 3))
                .when(syncQueueGateway).enqueueOrThrow(any());

        AtomicReference<reactor.core.publisher.Mono<InvocationResponse>> monoRef = new AtomicReference<>();
        assertThatCode(() -> monoRef.set(invocationService.invokeSyncReactive(
                "sync-reject-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        ))).doesNotThrowAnyException();

        assertThatThrownBy(() -> monoRef.get().block())
                .isInstanceOf(SyncQueueRejectedException.class);
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
}
