package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoOpInvocationEnqueuerTest {

    @Test
    void noOpReturnsEnumSingleton() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThat(enqueuer).isSameAs(NoOpInvocationEnqueuer.INSTANCE);
        assertThat(enqueuer.getClass().isEnum()).isTrue();
    }

    @Test
    void enabledReturnsFalse() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThat(enqueuer.enabled()).isFalse();
    }

    @Test
    void enqueueThrows() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThatThrownBy(() -> enqueuer.enqueue(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Async queue module not loaded");
    }

    @Test
    void decrementInFlightIsNoOp() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThatCode(() -> enqueuer.decrementInFlight("functionName"))
                .doesNotThrowAnyException();
    }

    @Test
    void tryAcquireSlotAlwaysReturnsTrue() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThat(enqueuer.tryAcquireSlot("functionName")).isTrue();
    }

    @Test
    void releaseSlotIsNoOp() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThatCode(() -> enqueuer.releaseSlot("functionName"))
                .doesNotThrowAnyException();
    }
}
