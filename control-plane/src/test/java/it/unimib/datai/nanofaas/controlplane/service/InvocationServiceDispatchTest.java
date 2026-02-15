package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvocationServiceDispatchTest {

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
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        RateLimiter rateLimiter = new RateLimiter();
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

        verify(queueManager).decrementInFlight("fn");
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
        verify(queueManager).decrementInFlight("local-fn");
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
