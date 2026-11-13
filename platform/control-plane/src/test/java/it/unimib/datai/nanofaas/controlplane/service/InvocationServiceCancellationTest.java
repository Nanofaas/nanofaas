package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore.AcquireResult;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A keyed request is prepared on another thread. If its caller goes away meanwhile, the
 * execution it built is dropped unseen: its key claim and its record must not be left behind.
 */
class InvocationServiceCancellationTest {

    @Test
    void aKeyedRequestCancelledWhileBeingPreparedLeavesNoClaimAndNoRecord() throws Exception {
        FunctionService functionService = mock(FunctionService.class);
        InvocationEnqueuer enqueuer = mock(InvocationEnqueuer.class);
        Metrics metrics = mock(Metrics.class);
        SyncQueueGateway syncQueueGateway = mock(SyncQueueGateway.class);
        when(enqueuer.queueStrategy()).thenReturn(InvocationEnqueuer.QueueStrategy.FUNCTION_QUEUE);
        ExecutionStore executionStore = new ExecutionStore();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        InvocationService service = TestWaiterCapacity.service(
                functionService, enqueuer, executionStore, idempotencyStore, metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, (queued, due, rejected) -> enqueuer.enqueue(queued),
                        mock(DispatcherRouter.class), metrics),
                "fn");
        FunctionSpec spec = new FunctionSpec("fn", "img", null, Map.of(), null, 1_000, 1, 10, 0, null,
                ExecutionMode.LOCAL, null, null, null, null);
        CountDownLatch preparing = new CountDownLatch(1);
        AtomicBoolean proceed = new AtomicBoolean();
        // Not interruptible, like the real preparation: cancelling only sets the thread's flag.
        when(functionService.get("fn")).thenAnswer(call -> {
            preparing.countDown();
            while (!proceed.get()) Thread.onSpinWait();
            return Optional.of(spec);
        });

        var caller = service.invokeSyncReactive("fn", new InvocationRequest("payload", Map.of()), "key-1", null, 1_000)
                .subscribe();
        assertThat(preparing.await(2, TimeUnit.SECONDS)).isTrue();
        caller.dispose();
        proceed.set(true);
        Thread.sleep(300); // the preparation finishes microseconds after it is released

        assertThat(executionStore.size()).as("no orphan execution record").isZero();
        assertThat(idempotencyStore.acquireOrGet("fn", "key-1").state())
                .as("the key is free for the caller's retry, not held by an abandoned claim")
                .isEqualTo(AcquireResult.State.CLAIMED);
    }
}
