package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.adaptiveControl;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static org.assertj.core.api.Assertions.assertThat;

class AdaptivePerPodConcurrencyControllerTest {

    private final AdaptivePerPodConcurrencyController controller = new AdaptivePerPodConcurrencyController();

    @Test
    void growsTheTargetWhileServiceTimeStaysAtItsBest() {
        FunctionSpec function = spec("echo", 24, adaptiveControl(2));

        // first sample only establishes the baseline: degradation is 0, so the target grows
        controller.computeEffectiveConcurrency(function, 1, 10, 100, 10_000);
        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(3);

        // same mean service time, upscale cooldown elapsed -> grow again
        controller.computeEffectiveConcurrency(function, 1, 20, 200, 11_500);
        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(4);
    }

    @Test
    void shrinksTheTargetWhenServiceTimeDegrades() {
        FunctionSpec function = spec("echo", 24, adaptiveControl(2));
        controller.computeEffectiveConcurrency(function, 1, 10, 100, 10_000);

        // 10 more invocations averaging 30ms against a 10ms baseline -> degradation 0.67
        int effective = controller.computeEffectiveConcurrency(function, 2, 20, 400, 12_500);

        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(2);
        assertThat(effective).isEqualTo(4);
    }

    @Test
    void holdsTheTargetWhileTheDownscaleCooldownIsPending() {
        FunctionSpec function = spec("echo", 24, adaptiveControl(2));
        controller.computeEffectiveConcurrency(function, 1, 10, 100, 10_000);
        controller.computeEffectiveConcurrency(function, 1, 20, 400, 12_500);
        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(2);

        // still degraded, but only 500ms after the last decrease (cooldown is 2000ms)
        controller.computeEffectiveConcurrency(function, 1, 30, 700, 13_000);

        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(2);
    }

    @Test
    void holdsTheTargetWhenNoInvocationCompletedInTheInterval() {
        FunctionSpec function = spec("echo", 24, adaptiveControl(2));
        controller.computeEffectiveConcurrency(function, 1, 10, 100, 10_000);
        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(3);

        controller.computeEffectiveConcurrency(function, 1, 10, 100, 60_000);

        assertThat(controller.currentTargetInFlightPerPod("echo", 0)).isEqualTo(3);
    }

    @Test
    void neverExceedsTheConfiguredConcurrency() {
        FunctionSpec function = spec("echo", 5, adaptiveControl(2));

        int effective = controller.computeEffectiveConcurrency(function, 4, 10, 100, 10_000);

        assertThat(effective).isEqualTo(5);
    }

    @Test
    void keepsPerFunctionStateIndependent() {
        FunctionSpec fast = spec("fast", 24, adaptiveControl(2));
        FunctionSpec slow = spec("slow", 24, adaptiveControl(2));
        controller.computeEffectiveConcurrency(fast, 1, 10, 100, 10_000);
        controller.computeEffectiveConcurrency(slow, 1, 10, 100, 10_000);

        controller.computeEffectiveConcurrency(fast, 1, 20, 200, 12_500);
        controller.computeEffectiveConcurrency(slow, 1, 20, 400, 12_500);

        assertThat(controller.currentTargetInFlightPerPod("fast", 0)).isEqualTo(4);
        assertThat(controller.currentTargetInFlightPerPod("slow", 0)).isEqualTo(2);
    }

    @Test
    void forgetsTheFunctionOnRemoval() {
        FunctionSpec function = spec("echo", 24, adaptiveControl(2));
        controller.computeEffectiveConcurrency(function, 1, 10, 100, 10_000);

        controller.removeFunctionState("echo");

        assertThat(controller.currentTargetInFlightPerPod("echo", 7)).isEqualTo(7);
    }

    @Test
    void leavesConcurrencyUntouchedForOtherModes() {
        FunctionSpec function = spec("echo", 6, ConcurrencySpecs.staticControl(2));

        assertThat(controller.computeEffectiveConcurrency(function, 3, 10, 100, 10_000)).isEqualTo(6);
    }
}
