package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncQueueWorkloadMetricsSourceTest {
    @Test
    void readsQueueAndCapacityAndReturnsZeroForUnknownFunctions() {
        QueueManager manager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec(
                "echo", "image", null, Map.of(), null,
                10, 2, 10, 3, null, ExecutionMode.LOCAL, null, null, null
        );
        manager.getOrCreate(spec);
        manager.enqueue(new InvocationTask("id", "echo", spec, null, null, null, null, 1,
                InvocationKind.ASYNC));
        AsyncQueueWorkloadMetricsSource source = new AsyncQueueWorkloadMetricsSource(manager);

        assertThat(source.queueDepth("echo")).isOne();
        assertThat(source.inFlight("echo")).isZero();
        assertThat(source.effectiveConcurrency("echo")).isEqualTo(2);
        assertThat(source.dispatchableBacklog("echo")).isOne();
        assertThat(source.queueDepth("missing")).isZero();
        assertThat(source.inFlight("missing")).isZero();
        assertThat(source.effectiveConcurrency("missing")).isZero();
        assertThat(source.dispatchableBacklog("missing")).isZero();
    }
}
