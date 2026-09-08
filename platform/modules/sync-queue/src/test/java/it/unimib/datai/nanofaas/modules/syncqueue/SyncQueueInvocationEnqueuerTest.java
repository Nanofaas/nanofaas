package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The retry path calls {@code enqueue} without consulting {@code enabled()}, so an adapter that
 * always answers "refused" turns every retry into a spurious queue-full completion. This pins the
 * seam: refusal has to mean the sync queue actually refused.
 */
class SyncQueueInvocationEnqueuerTest {

    private final SyncQueueGateway gateway = mock(SyncQueueGateway.class);
    private final SyncQueueInvocationEnqueuer enqueuer =
            new SyncQueueInvocationEnqueuer(new FunctionCapacityRegistry(), null, ignored -> { }, gateway);
    private final InvocationTask task = task("fn");

    @Test
    void enqueueDelegatesToSyncGateway() {
        assertThat(enqueuer.enqueue(task)).isTrue();
        verify(gateway).enqueueOrThrow(task);
    }

    @Test
    void enqueueReturnsFalseWhenSyncQueueRejects() {
        doThrow(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 2))
                .when(gateway).enqueueOrThrow(task);

        assertThat(enqueuer.enqueue(task)).isFalse();
    }

    @Test
    void enqueueDoesNotHideUnexpectedFailures() {
        doThrow(new IllegalStateException("boom")).when(gateway).enqueueOrThrow(task);

        assertThatThrownBy(() -> enqueuer.enqueue(task))
                .isInstanceOf(IllegalStateException.class);
    }

    private static InvocationTask task(String functionName) {
        FunctionSpec spec = new FunctionSpec(functionName, "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
        return new InvocationTask("exec-1", functionName, spec,
                new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }
}
