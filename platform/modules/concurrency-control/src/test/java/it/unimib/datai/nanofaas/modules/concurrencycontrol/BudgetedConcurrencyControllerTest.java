package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import it.unimib.datai.nanofaas.controlplane.service.RecordingWorkloadMetricsSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.budgetedControl;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static org.assertj.core.api.Assertions.assertThat;

class BudgetedConcurrencyControllerTest {

    private final RecordingMetricsSource metricsSource = new RecordingMetricsSource();
    private final ConcurrencyControlMetrics concurrencyMetrics = new ConcurrencyControlMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final BudgetedConcurrencyController controller = new BudgetedConcurrencyController();

    // Ticks have to advance: throughput is completions per unit of time, so a controller handed
    // the same instant twice has been shown nothing at all.
    private long tick = 0;

    private long nextTick() {
        tick += 5_000;
        return tick;
    }

    private static BudgetedConcurrencyController.FunctionObservation observation(
            FunctionSpec spec, int inFlight, long count, double totalMs) {
        return new BudgetedConcurrencyController.FunctionObservation(spec, inFlight, count, totalMs);
    }

    @Test
    void the_limits_never_add_up_to_more_than_the_budget() {
        FunctionSpec a = spec("a", 64, budgetedControl(100, 1));
        FunctionSpec b = spec("b", 64, budgetedControl(100, 1));
        List<BudgetedConcurrencyController.FunctionObservation> first = List.of(
                observation(a, 8, 0, 0), observation(b, 8, 0, 0));

        controller.apply(first, 20, metricsSource, nextTick());
        // Both inside their SLO and both busy enough for the knee to be well above the budget,
        // so the budget is what binds rather than the knee.
        for (int round = 0; round < 5; round++) {
            long served = 100_000L * (round + 1);
            controller.apply(
                    List.of(observation(a, 8, served, served * 5), observation(b, 8, served, served * 5)),
                    20,
                    metricsSource, nextTick());
        }

        assertThat(metricsSource.effective.values().stream().mapToInt(Integer::intValue).sum())
                .isLessThanOrEqualTo(20);
    }

    @Test
    void a_stricter_slo_is_served_before_a_looser_one_when_the_budget_is_short() {
        FunctionSpec gold = spec("gold", 64, budgetedControl(50, 4));
        FunctionSpec bulk = spec("bulk", 64, budgetedControl(50, 1));
        controller.apply(List.of(observation(gold, 8, 0, 0), observation(bulk, 8, 0, 0)), 16, metricsSource, nextTick());

        // Both want far more than 16 between them, so the weights are what decides.
        for (int round = 0; round < 5; round++) {
            long served = 100_000L * (round + 1);
            controller.apply(
                    List.of(
                            observation(gold, 8, served, served * 5),
                            observation(bulk, 8, served, served * 5)),
                    16,
                    metricsSource, nextTick());
        }

        assertThat(metricsSource.effective.get("gold"))
                .isGreaterThan(metricsSource.effective.get("bulk"));
    }

    @Test
    void every_function_keeps_a_limit_it_can_serve_with() {
        FunctionSpec a = spec("a", 64, budgetedControl(100, 1));
        FunctionSpec b = spec("b", 64, budgetedControl(100, 50));

        controller.apply(List.of(observation(a, 1, 0, 0), observation(b, 1, 0, 0)), 4, metricsSource, nextTick());

        assertThat(metricsSource.effective.values()).allSatisfy(
                limit -> assertThat(limit).isGreaterThanOrEqualTo(1));
    }

    @Test
    void publishes_the_mode_so_the_limit_can_be_attributed() {
        FunctionSpec a = spec("a", 64, budgetedControl(100, 1));

        controller.apply(List.of(observation(a, 2, 0, 0)), 32, metricsSource, nextTick());

        assertThat(metricsSource.modes).containsEntry("a", ConcurrencyControlMode.BUDGETED);
    }

    @Test
    void a_function_that_slows_down_gives_room_back_to_the_others() {
        // The property the mode exists for: one function's trouble becomes another's capacity,
        // decided in one place instead of discovered through interference.
        FunctionSpec a = spec("a", 64, budgetedControl(100, 1));
        FunctionSpec b = spec("b", 64, budgetedControl(100, 1));
        controller.apply(List.of(observation(a, 8, 0, 0), observation(b, 8, 0, 0)), 24, metricsSource, nextTick());
        controller.apply(
                List.of(observation(a, 8, 1000, 20_000), observation(b, 8, 1000, 20_000)),
                24,
                metricsSource, nextTick());
        int bBefore = metricsSource.effective.get("b");

        // `a` is now far outside its SLO while `b` is well inside it.
        controller.apply(
                List.of(observation(a, 8, 2000, 620_000), observation(b, 8, 2000, 40_000)),
                24,
                metricsSource, nextTick());

        assertThat(metricsSource.effective.get("a")).isLessThan(metricsSource.effective.get("b"));
        assertThat(metricsSource.effective.get("b")).isGreaterThanOrEqualTo(bBefore);
    }

    private static final class RecordingMetricsSource implements RecordingWorkloadMetricsSource {
        private final Map<String, Integer> effective = new HashMap<>();
        private final Map<String, ConcurrencyControlMode> modes = new HashMap<>();

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
            effective.put(functionName, value);
        }

        @Override
        public void updateConcurrencyController(
                String functionName, ConcurrencyControlMode mode, int target) {
            modes.put(functionName, mode);
        }
    }
}
