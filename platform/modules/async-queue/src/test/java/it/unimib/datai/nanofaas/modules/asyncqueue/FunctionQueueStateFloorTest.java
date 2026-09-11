package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FunctionQueueStateFloorTest {
    @Test
    void duplicateLeaseReleaseCannotConsumeAnotherAttempt() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionQueueState state = new FunctionQueueState("fn", 10, registry.register("fn", 2));
        var first = registry.tryAcquireLease(state.capacity().generation(), held -> { });
        var second = registry.tryAcquireLease(state.capacity().generation(), held -> { });
        first.release();
        first.release();
        assertThat(state.inFlight()).isEqualTo(1);
        second.release();
        second.release();
        assertThat(state.inFlight()).isZero();
    }

    @Test
    void releasedLeaseCannotConsumeCapacityAcquiredLater() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionQueueState state = new FunctionQueueState("fn", 10, registry.register("fn", 1));
        var first = registry.tryAcquireLease(state.capacity().generation(), held -> { });
        first.release();
        var next = registry.tryAcquireLease(state.capacity().generation(), held -> { });
        assertThat(next).isNotNull();
        first.release();
        assertThat(state.inFlight()).isEqualTo(1);
        next.release();
        assertThat(state.inFlight()).isZero();
    }
}
