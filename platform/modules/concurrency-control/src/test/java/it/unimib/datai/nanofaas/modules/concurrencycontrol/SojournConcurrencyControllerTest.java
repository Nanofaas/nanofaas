package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.sojournControl;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static org.assertj.core.api.Assertions.assertThat;

class SojournConcurrencyControllerTest {

    private final RecordingMetricsSource metricsSource = new RecordingMetricsSource();
    private final SojournConcurrencyController controller = new SojournConcurrencyController();

    // The controller reads a cumulative timer, so ticks have to advance and totals accumulate:
    // handed the same instant twice it has been shown nothing.
    private long tickAt = 0;
    private long served = 0;
    private double totalMs = 0;

    /** Feeds one interval in which `count` requests each took `sojournMs`. */
    private int tick(FunctionSpec spec, int inFlight, int count, double sojournMs) {
        tickAt += 5_000;
        served += count;
        totalMs += count * sojournMs;
        return controller.apply(
                new SojournConcurrencyController.FunctionObservation(spec, inFlight, served, totalMs),
                metricsSource,
                tickAt);
    }

    @Test
    void an_interval_that_completed_nothing_moves_nothing() {
        // An idle interval is evidence of nothing, and treating it as a reading would move the
        // limit on the strength of an absence.
        FunctionSpec fn = spec("fn", 16, sojournControl(10, 1, 8));
        int opening = tick(fn, 4, 4, 50);

        int afterIdle = tick(fn, 0, 0, 0);

        assertThat(afterIdle).isEqualTo(opening);
    }

    @Test
    void a_function_inside_its_promise_is_left_alone() {
        // The minimum is somewhere below, but hunting for it costs churn in the enforcing queue for
        // latency the caller was already promised.
        FunctionSpec fn = spec("fn", 16, sojournControl(100, 1, 8));
        List<Integer> limits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            limits.add(tick(fn, 4, 100, 20));
        }

        assertThat(limits).containsOnly(limits.getFirst());
    }

    @Test
    void it_keeps_going_while_the_move_is_helping() {
        FunctionSpec fn = spec("fn", 16, sojournControl(5, 1, 8));
        // Every interval is outside the promise and each is better than the one before, so the
        // search has no reason to turn round.
        int first = tick(fn, 4, 100, 100);
        int second = tick(fn, 4, 100, 80);
        int third = tick(fn, 4, 100, 60);

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void it_turns_round_when_the_move_made_things_worse() {
        // The property the mode exists for. Sojourn has an interior minimum — the wait falls as the
        // limit rises while service time climbs — so overshooting it has to be detected and undone.
        // A threshold rule cannot do this; it would read "slow" and shrink, lengthening the queue.
        FunctionSpec fn = spec("fn", 16, sojournControl(5, 1, 8));
        tick(fn, 4, 100, 100);
        int climbing = tick(fn, 4, 100, 70);
        int pastTheMinimum = tick(fn, 4, 100, 140);

        assertThat(climbing).isGreaterThan(1);
        assertThat(pastTheMinimum).isLessThan(climbing);
    }

    @Test
    void it_settles_around_the_minimum_of_a_curve_it_has_never_been_told() {
        // A U-shaped sojourn curve with its minimum at 4, given only as measurements. The
        // controller is never told where the minimum is; it has to find it by moving.
        FunctionSpec fn = spec("fn", 16, sojournControl(1, 1, 8));
        Map<Integer, Double> curve = Map.of(
                1, 90.0, 2, 60.0, 3, 40.0, 4, 30.0, 5, 42.0, 6, 58.0, 7, 75.0, 8, 95.0);
        int limit = tick(fn, 1, 100, curve.get(1));
        List<Integer> visited = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            limit = tick(fn, limit, 100, curve.get(limit));
            visited.add(limit);
        }

        // The tail of the walk stays in the neighbourhood of the minimum rather than running to a
        // bound, which is what a monotone signal would have produced.
        List<Integer> tail = visited.subList(visited.size() - 8, visited.size());
        assertThat(tail).isNotEmpty().allSatisfy(value -> assertThat(value).isBetween(3, 5));
    }

    @Test
    void it_does_not_chase_noise() {
        // Two readings of the same operating point differ by ordinary variance. Without a dead band
        // the controller reads that variance as a gradient and oscillates forever.
        FunctionSpec fn = spec("fn", 16, sojournControl(5, 1, 8));
        tick(fn, 4, 100, 50);
        int before = tick(fn, 4, 100, 50);
        int afterTinyWorsening = tick(fn, 4, 100, 51);

        // 2% is inside the band, so the search carries on the way it was going rather than turning.
        assertThat(afterTinyWorsening).isGreaterThan(before);
    }

    @Test
    void it_never_leaves_the_window_it_was_given() {
        FunctionSpec fn = spec("fn", 16, sojournControl(1, 3, 5));
        List<Integer> limits = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            limits.add(tick(fn, 4, 100, 40 + i * 10));
        }

        assertThat(limits).isNotEmpty().allSatisfy(limit -> assertThat(limit).isBetween(3, 5));
    }

    @Test
    void it_rests_against_the_ceiling_while_the_ceiling_is_still_the_best_it_can_do() {
        // Turning round here would give up throughput on every other tick for nothing: the search
        // wants to go further and is not allowed to, which is not the same as having overshot.
        FunctionSpec fn = spec("fn", 16, sojournControl(1, 1, 3));
        List<Integer> limits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            limits.add(tick(fn, 3, 100, 100 - i * 10.0));
        }

        assertThat(limits.subList(3, limits.size())).containsOnly(3);
    }

    @Test
    void it_stops_pressing_a_bound_once_pressing_it_stops_helping() {
        // Flat against the ceiling means the search has nowhere to go and nothing left to learn
        // there, so it has to walk back and explore the way it still can.
        FunctionSpec fn = spec("fn", 16, sojournControl(1, 1, 3));
        int whileImproving = 0;
        for (int i = 0; i < 5; i++) {
            whileImproving = tick(fn, 3, 100, 100 - i * 10.0);
        }
        // The sixth reading is the first that is no better than the one before it.
        int onceFlat = tick(fn, 3, 100, 60);

        assertThat(whileImproving).isEqualTo(3);
        assertThat(onceFlat).isEqualTo(2);
    }

    @Test
    void the_ceiling_is_the_configured_concurrency_when_no_maximum_is_set() {
        FunctionSpec fn = spec("fn", 2, sojournControl(1, 1, null));
        List<Integer> limits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            limits.add(tick(fn, 2, 100, 100 - i * 5.0));
        }

        assertThat(limits).isNotEmpty().allSatisfy(limit -> assertThat(limit).isLessThanOrEqualTo(2));
    }

    @Test
    void it_publishes_the_mode_so_the_limit_can_be_attributed() {
        FunctionSpec fn = spec("fn", 16, sojournControl(10, 1, 8));

        tick(fn, 4, 10, 50);

        assertThat(metricsSource.modes).containsEntry("fn", ConcurrencyControlMode.SOJOURN);
        assertThat(metricsSource.effective).containsKey("fn");
    }

    @Test
    void a_removed_function_leaves_no_search_behind() {
        FunctionSpec fn = spec("fn", 16, sojournControl(5, 1, 8));
        tick(fn, 4, 100, 100);
        tick(fn, 4, 100, 60);

        controller.removeFunctionState("fn");
        // A fresh search opens from the current in-flight count rather than resuming a walk whose
        // measurements belong to a function that no longer exists.
        int reopened = tick(fn, 1, 100, 60);

        assertThat(reopened).isEqualTo(2);
    }

    private static final class RecordingMetricsSource implements ScalingMetricsSource {
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
