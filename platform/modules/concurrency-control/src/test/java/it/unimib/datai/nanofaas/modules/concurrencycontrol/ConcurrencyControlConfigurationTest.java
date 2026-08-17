package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The module is inert without a queue module, and used to be inert silently: the core registers a
 * no-op {@code ScalingMetricsSource} whenever nothing else supplies one, so the
 * {@code @ConditionalOnBean(ScalingMetricsSource.class)} guard on the configuration was satisfied
 * either way. The governor then read depth 0 and in-flight 0 forever and wrote its limits into a
 * sink that enforces nothing.
 */
class ConcurrencyControlConfigurationTest {

    @Test
    void refusesToStartWhenNoModuleSuppliesQueueState() {
        ScalingMetricsSource noOp = ScalingMetricsSource.noOp();

        assertThatThrownBy(() -> new ConcurrencyControlConfiguration(noOp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("async-queue");
    }

    @Test
    void startsAgainstASourceThatReportsRealQueueState() {
        ScalingMetricsSource source = new StubMetricsSource();

        assertThatCode(() -> new ConcurrencyControlConfiguration(source))
                .doesNotThrowAnyException();
    }

    private static final class StubMetricsSource implements ScalingMetricsSource {
        @Override
        public int queueDepth(String functionName) {
            return 0;
        }

        @Override
        public int inFlight(String functionName) {
            return 0;
        }

        @Override
        public void setEffectiveConcurrency(String functionName, int value) {
            // reading is what this test is about
        }

        @Override
        public void updateConcurrencyController(String functionName,
                                                ConcurrencyControlMode mode,
                                                int targetInFlightPerPod) {
            // reading is what this test is about
        }
    }
}
