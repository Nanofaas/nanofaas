package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.budgetedControl;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static org.assertj.core.api.Assertions.assertThat;

class SloDemandEstimatorTest {

    private final SloDemandEstimator estimator = new SloDemandEstimator();

    @Test
    void asks_for_more_while_the_function_is_inside_its_slo() {
        FunctionSpec function = spec("echo", 64, budgetedControl(100, 1));

        // Comfortably inside the SLO, so the gradient is 1 and the ask grows by the headroom
        // term - the slack that stops the next burst from being rejected.
        ConcurrencyDemand first = estimator.estimate(function, 20, 1000, 4);
        ConcurrencyDemand second = estimator.estimate(function, 20, 1000, 4);

        assertThat(second.desired()).isGreaterThan(first.desired());
    }

    @Test
    void asks_for_less_once_the_slo_is_actually_being_broken() {
        FunctionSpec function = spec("echo", 64, budgetedControl(100, 1));
        estimator.recordGrant("echo", 40);

        ConcurrencyDemand demand = estimator.estimate(function, 200, 1000, 40);

        // Twice the target, so the gradient is 0.5 and the ask roughly halves plus headroom.
        assertThat(demand.desired()).isLessThan(40);
    }

    @Test
    void one_slow_interval_cannot_collapse_the_limit() {
        // A cold start or a GC pause is not a reason to shut a function down to its floor.
        FunctionSpec function = spec("echo", 64, budgetedControl(100, 1));
        estimator.recordGrant("echo", 40);

        ConcurrencyDemand demand = estimator.estimate(function, 100_000, 1000, 40);

        assertThat(demand.desired()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void an_interval_with_no_completions_changes_nothing() {
        FunctionSpec function = spec("echo", 64, budgetedControl(100, 1));
        estimator.recordGrant("echo", 12);

        assertThat(estimator.estimate(function, 0, 0, 0).desired()).isEqualTo(12);
    }

    @Test
    void never_asks_for_more_than_the_function_was_registered_to_allow() {
        FunctionSpec function = spec("echo", 6, budgetedControl(100, 1));
        estimator.recordGrant("echo", 6);

        for (int tick = 0; tick < 20; tick++) {
            assertThat(estimator.estimate(function, 1, 100_000, 6).desired()).isLessThanOrEqualTo(6);
        }
    }

    @Test
    void steps_from_what_it_was_granted_rather_than_what_it_asked_for() {
        // A function starved by the budget would otherwise keep asking from an imagined
        // position and lurch when capacity freed up.
        FunctionSpec function = spec("echo", 64, budgetedControl(100, 1));
        estimator.estimate(function, 20, 1000, 4);
        estimator.recordGrant("echo", 2);

        assertThat(estimator.estimate(function, 20, 1000, 2).desired()).isLessThan(8);
    }

    @Test
    void carries_the_weight_the_function_declared() {
        FunctionSpec function = spec("echo", 64, budgetedControl(100, 3.5));

        assertThat(estimator.estimate(function, 20, 1000, 4).weight()).isEqualTo(3.5);
    }

    @Test
    void stops_growing_once_more_concurrency_stops_buying_completions() {
        // Measured motivation: told it could take 10ms, the SLO rule alone grew to four times the
        // concurrency of the gradient mode and delivered 6% fewer requests. Little's law puts a
        // ceiling on the ask that saturating throughput cannot push any higher.
        FunctionSpec function = spec("echo", 64, budgetedControl(1000, 1));
        estimator.recordGrant("echo", 4);

        // Well inside the SLO throughout, so the gradient would grow every tick; throughput is
        // pinned, so the knee is not moving.
        int last = 0;
        for (int tick = 0; tick < 15; tick++) {
            ConcurrencyDemand demand = estimator.estimate(function, 2, 2000, 4);
            last = demand.desired();
            estimator.recordGrant("echo", last);
        }

        // lambda_max x S_min = 2000/s x 2ms = 4 concurrent, plus burst headroom.
        assertThat(last).isLessThanOrEqualTo(7);
    }

    @Test
    void keeps_growing_while_the_extra_concurrency_is_still_buying_completions() {
        FunctionSpec function = spec("echo", 64, budgetedControl(1000, 1));
        estimator.recordGrant("echo", 4);

        int first = estimator.estimate(function, 2, 2_000, 4).desired();
        estimator.recordGrant("echo", first);
        int second = estimator.estimate(function, 2, 20_000, first).desired();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void an_slo_breach_shrinks_the_ask_whatever_the_throughput_argument_says() {
        // A broken promise is not negotiable against throughput.
        FunctionSpec function = spec("echo", 64, budgetedControl(10, 1));
        estimator.recordGrant("echo", 40);

        assertThat(estimator.estimate(function, 100, 100_000, 40).desired()).isLessThan(40);
    }
}
