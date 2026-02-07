package it.unimib.datai.nanofaas.controlplane.queue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionQueueStateFloorTest {

    @Test
    void releaseSlot_withoutAcquire_neverGoesNegative() {
        FunctionQueueState state = new FunctionQueueState("fn", 10, 2);

        // Release without any acquire
        state.releaseSlot();
        assertThat(state.inFlight()).isEqualTo(0);

        // Release multiple times
        state.releaseSlot();
        state.releaseSlot();
        state.releaseSlot();
        assertThat(state.inFlight()).isEqualTo(0);
    }

    @Test
    void releaseSlot_moreThanAcquired_floorsAtZero() {
        FunctionQueueState state = new FunctionQueueState("fn", 10, 5);

        // Acquire 2 slots
        state.tryAcquireSlot();
        state.tryAcquireSlot();
        assertThat(state.inFlight()).isEqualTo(2);

        // Release 4 times (2 more than acquired)
        state.releaseSlot();
        state.releaseSlot();
        state.releaseSlot();
        state.releaseSlot();
        assertThat(state.inFlight()).isEqualTo(0);
    }

    @Test
    void decrementInFlight_withoutIncrement_neverGoesNegative() {
        FunctionQueueState state = new FunctionQueueState("fn", 10, 2);

        state.decrementInFlight();
        assertThat(state.inFlight()).isEqualTo(0);

        state.decrementInFlight();
        state.decrementInFlight();
        assertThat(state.inFlight()).isEqualTo(0);
    }

    @Test
    void releaseSlot_afterFloor_allowsNewAcquire() {
        FunctionQueueState state = new FunctionQueueState("fn", 10, 1);

        // Over-release
        state.releaseSlot();
        state.releaseSlot();
        assertThat(state.inFlight()).isEqualTo(0);

        // Should still be able to acquire
        assertThat(state.tryAcquireSlot()).isTrue();
        assertThat(state.inFlight()).isEqualTo(1);
    }
}
