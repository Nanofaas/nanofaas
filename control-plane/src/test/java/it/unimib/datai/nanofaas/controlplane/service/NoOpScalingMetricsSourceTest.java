package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpScalingMetricsSourceTest {

    @Test
    void returnsZeros() {
        ScalingMetricsSource metricsSource = ScalingMetricsSource.noOp();

        assertThat(metricsSource.queueDepth("functionName")).isZero();
        assertThat(metricsSource.inFlight("functionName")).isZero();
    }

    @Test
    void mutatorsAreNoOp() {
        ScalingMetricsSource metricsSource = ScalingMetricsSource.noOp();

        assertThatCode(() -> metricsSource.setEffectiveConcurrency("functionName", 3))
                .doesNotThrowAnyException();
        assertThatCode(() -> metricsSource.updateConcurrencyController(
                "functionName",
                ConcurrencyControlMode.ADAPTIVE_PER_POD,
                5
        )).doesNotThrowAnyException();
    }
}
