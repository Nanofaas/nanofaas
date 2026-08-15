package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.staticControl;
import static org.assertj.core.api.Assertions.assertThat;

class StaticPerPodConcurrencyControllerTest {

    private final StaticPerPodConcurrencyController controller = new StaticPerPodConcurrencyController();

    @Test
    void multipliesTargetByReadyReplicas() {
        FunctionSpec function = spec("echo", 12, staticControl(2));

        assertThat(controller.computeEffectiveConcurrency(function, 3)).isEqualTo(6);
    }

    @Test
    void neverExceedsTheConfiguredConcurrency() {
        FunctionSpec function = spec("echo", 8, staticControl(3));

        assertThat(controller.computeEffectiveConcurrency(function, 5)).isEqualTo(8);
    }

    @Test
    void treatsZeroReadyReplicasAsOne() {
        FunctionSpec function = spec("echo", 6, staticControl(2));

        assertThat(controller.computeEffectiveConcurrency(function, 0)).isEqualTo(2);
    }

    @Test
    void leavesConcurrencyUntouchedForOtherModes() {
        FunctionSpec function = spec("echo", 6, ConcurrencySpecs.adaptiveControl(2));

        assertThat(controller.computeEffectiveConcurrency(function, 3)).isEqualTo(6);
    }
}
