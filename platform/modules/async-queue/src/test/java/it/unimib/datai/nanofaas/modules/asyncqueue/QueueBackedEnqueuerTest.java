package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QueueBackedEnqueuerTest {

    @Test
    void releaseDispatchSlot_delegatesToQueueManager() {
        QueueManager queueManager = mock(QueueManager.class);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);

        enqueuer.releaseDispatchSlot("fn");

        verify(queueManager).releaseSlot("fn");
    }

    @Test
    void releaseAfterRemovalDrainsRetiredGenerationBeforeReregistration() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        QueueManager queueManager = new QueueManager(meters,
                new it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics(meters), capacity);
        QueueBackedEnqueuer enqueuer = new QueueBackedEnqueuer(queueManager);
        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null,
                1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);

        assertThat(queueManager.getOrCreate(spec).tryAcquireSlot()).isTrue();
        queueManager.remove("fn");
        assertThatThrownBy(() -> queueManager.getOrCreate(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active slots");

        enqueuer.releaseDispatchSlot("fn");
        assertThat(queueManager.getOrCreate(spec).tryAcquireSlot()).isTrue();
        assertThat(capacity.inFlight("fn")).isEqualTo(1);
    }
}
