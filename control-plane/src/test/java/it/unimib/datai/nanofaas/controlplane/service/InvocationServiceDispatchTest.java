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
    private IdempotencyStore idempotencyStore;
    private ExecutionCompletionHandler completionHandler;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
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
        when(metrics.timers(anyString())).thenAnswer(invocation -> new Metrics.FunctionTimers(
                metrics.latency(invocation.getArgument(0)),
                metrics.initDuration(invocation.getArgument(0)),
                metrics.queueWait(invocation.getArgument(0)),
                metrics.e2eLatency(invocation.getArgument(0))
        ));
    }

    @Test
    void invokeSync_existingSuccessfulExecution_returnsMappedResponseWithoutDispatch() throws InterruptedException {
        FunctionSpec spec = functionSpec("replay-success-fn", ExecutionMode.LOCAL);
        when(functionService.get("replay-success-fn")).thenReturn(Optional.of(spec));

        InvocationTask task = task("exec-replay-success", "replay-success-fn", ExecutionMode.LOCAL);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        record.markSuccess("replayed-ok");
        executionStore.put(record);
        idempotencyStore.put("replay-success-fn", "idem-1", record.executionId());

        InvocationResponse response = invocationService.invokeSync(
                "replay-success-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-1",
                "trace-1",
                1_000
        );

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
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        record.markSuccess("reactive-replayed-ok");
        executionStore.put(record);
        idempotencyStore.put("replay-reactive-success-fn", "idem-reactive-success", record.executionId());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-reactive-success-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-reactive-success",
                "trace-1",
                1_000
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.executionId()).isEqualTo("exec-reactive-replay-success");
        assertThat(response.status()).isEqualTo("success");
        assertThat(response.output()).isEqualTo("reactive-replayed-ok");
        verify(syncQueueGateway, never()).enqueueOrThrow(any());
        verify(enqueuer, never()).enqueue(any());
        verifyNoInteractions(dispatcherRouter);
    }

    @Test
    void invokeSync_existingTimeoutExecution_returnsTimeoutWithoutRedispatch() throws InterruptedException {
        FunctionSpec spec = functionSpec("replay-sync-timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("replay-sync-timeout-fn")).thenReturn(Optional.of(spec));

        InvocationTask task = task("exec-sync-replay-timeout", "replay-sync-timeout-fn", ExecutionMode.LOCAL);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        record.markTimeout();
        executionStore.put(record);
        idempotencyStore.put("replay-sync-timeout-fn", "idem-sync-timeout", record.executionId());

        InvocationResponse response = invocationService.invokeSync(
                "replay-sync-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-sync-timeout",
                "trace-1",
                1_000
        );

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
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        record.markTimeout();
        executionStore.put(record);
        idempotencyStore.put("replay-timeout-fn", "idem-timeout", record.executionId());

        InvocationResponse response = invocationService.invokeSyncReactive(
                "replay-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout",
                "trace-1",
                1_000
        ).block();

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

    @Test
    void invokeSync_andReactiveQueueTimeoutSurfaceTheSameContract() {
        FunctionSpec spec = functionSpec("queue-timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("queue-timeout-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(true);
        when(syncQueueGateway.retryAfterSeconds()).thenReturn(9);
        doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            invocationService.completeExecution(
                    task.executionId(),
                    DispatchResult.warm(InvocationResult.error("QUEUE_TIMEOUT", "queue wait exceeded"))
            );
            return null;
        }).when(syncQueueGateway).enqueueOrThrow(any());

        assertThatThrownBy(() -> invocationService.invokeSync(
                "queue-timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-sync-timeout",
                null,
                1_000
        )).isInstanceOfSatisfying(SyncQueueRejectedException.class, ex -> {
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
    void invokeSync_timeoutRemainsTerminalWhenLateSuccessArrives() throws Exception {
        CompletableFuture<DispatchResult> dispatchFuture = new CompletableFuture<>();
        FunctionSpec spec = functionSpec("timeout-fn", ExecutionMode.LOCAL);
        when(functionService.get("timeout-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.enabled()).thenReturn(false);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(dispatchFuture);

        InvocationResponse first = invocationService.invokeSync(
                "timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout",
                null,
                10
        );

        assertThat(first.status()).isEqualTo("timeout");

        dispatchFuture.complete(DispatchResult.warm(InvocationResult.success("late-ok")));

        InvocationResponse second = invocationService.invokeSync(
                "timeout-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout",
                null,
                10
        );

        assertThat(second.status()).isEqualTo("timeout");
        assertThat(invocationService.getStatus(first.executionId())).get()
                .extracting(status -> status.status())
                .isEqualTo("timeout");
    }

    @Test
    void invokeSyncReactive_timeoutRemainsTerminalWhenLateSuccessArrives() {
        CompletableFuture<DispatchResult> dispatchFuture = new CompletableFuture<>();
        FunctionSpec spec = functionSpec("timeout-reactive-fn", ExecutionMode.LOCAL);
        when(functionService.get("timeout-reactive-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.enabled()).thenReturn(false);
        when(dispatcherRouter.dispatchLocal(any())).thenReturn(dispatchFuture);

        InvocationResponse first = invocationService.invokeSyncReactive(
                "timeout-reactive-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout-reactive",
                null,
                10
        ).block();

        assertThat(first).isNotNull();
        assertThat(first.status()).isEqualTo("timeout");

        dispatchFuture.complete(DispatchResult.warm(InvocationResult.success("late-ok")));

        InvocationResponse second = invocationService.invokeSyncReactive(
                "timeout-reactive-fn",
                new InvocationRequest("payload", Map.of()),
                "idem-timeout-reactive",
                null,
                10
        ).block();

        assertThat(second).isNotNull();
        assertThat(second.status()).isEqualTo("timeout");
        assertThat(invocationService.getStatus(first.executionId())).get()
                .extracting(status -> status.status())
                .isEqualTo("timeout");
    }

    @Test
    void invokeAsync_staleIdempotencyMapping_createsOnlyOneFreshExecutionUnderContention() throws Exception {
        FunctionSpec spec = functionSpec("stale-idem-fn", ExecutionMode.LOCAL);
        when(functionService.get("stale-idem-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.enabled()).thenReturn(true);
        when(enqueuer.enqueue(any())).thenReturn(true);

        IdempotencyStore staleStore = new IdempotencyStore(Duration.ofMinutes(15));
        staleStore.put("stale-idem-fn", "same-key", "evicted-execution");
        InvocationService racingService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                staleStore,
                new RateLimiter(),
                metrics,
                syncQueueGateway,
                completionHandler
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
            staleStore.shutdown();
        }
    }

    @Test
    void invokeAsync_staleIdempotencyMapping_doesNotAllowReplacementBeforeWinnerIsPublished() throws Exception {
        FunctionSpec spec = functionSpec("stale-publication-fn", ExecutionMode.LOCAL);
        when(functionService.get("stale-publication-fn")).thenReturn(Optional.of(spec));
        when(syncQueueGateway.enabled()).thenReturn(false);
        when(enqueuer.enabled()).thenReturn(true);
        when(enqueuer.enqueue(any())).thenReturn(true);

        BlockingExecutionStore blockedStore = new BlockingExecutionStore();
        IdempotencyStore staleStore = new IdempotencyStore(Duration.ofMinutes(15));
        staleStore.put("stale-publication-fn", "same-key", "evicted-execution");
        InvocationService racingService = new InvocationService(
                functionService,
                enqueuer,
                blockedStore,
                staleStore,
                new RateLimiter(),
                metrics,
                syncQueueGateway,
                new ExecutionCompletionHandler(blockedStore, enqueuer, dispatcherRouter, metrics)
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
            staleStore.shutdown();
            blockedStore.shutdown();
        }
    }

    private static final class BlockingExecutionStore extends ExecutionStore {
        private final CountDownLatch firstPutStarted = new CountDownLatch(1);
        private final CountDownLatch hiddenExecutionLookup = new CountDownLatch(1);
        private final CountDownLatch allowFirstPutToComplete = new CountDownLatch(1);
        private final AtomicBoolean blockNextPut = new AtomicBoolean(true);
        private volatile String blockedExecutionId;

        @Override
        public void put(ExecutionRecord record) {
            if (blockNextPut.compareAndSet(true, false)) {
                blockedExecutionId = record.executionId();
                firstPutStarted.countDown();
                try {
                    allowFirstPutToComplete.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            super.put(record);
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
