package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(enqueuer.supportsAsync()).isFalse();
    }

    @Test
    void enqueueThrows() {
        InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

        assertThatThrownBy(() -> enqueuer.enqueue(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Async queue module not loaded");
    }

    @Test
    void admissionDoesNotExposeDispatchOwnership() {
        assertThat(InvocationEnqueuer.noOp().queueStrategy()).isEqualTo(InvocationEnqueuer.QueueStrategy.DIRECT);
        assertThat(InvocationEnqueuer.class.getMethods()).noneMatch(method -> method.getName().contains("Slot"));
    }
}
