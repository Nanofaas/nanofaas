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

    // Cumulative timers, so ticks advance and totals accumulate: handed the same instant twice the
    // controller has been shown no interval at all.
    private long tickAt = 0;
    private long served = 0;
    private double serviceTotalMs = 0;
    private double e2eTotalMs = 0;

    /**
     * One interval: `count` requests completed, each taking `serviceMs` in the function and
     * `sojournMs` from enqueue to completion, with `queueDepth` waiting at the end of it.
     */
    private int tick(FunctionSpec spec, int inFlight, int queueDepth,
                     int count, double serviceMs, double sojournMs) {
        tickAt += 5_000;
        served += count;
        serviceTotalMs += count * serviceMs;
        e2eTotalMs += count * sojournMs;
        metricsSource.queueDepths.put(spec.name(), queueDepth);
        return controller.apply(
                new SojournConcurrencyController.FunctionObservation(
                        spec, inFlight, served, e2eTotalMs, served, serviceTotalMs),
                metricsSource,
                tickAt);
    }

    @Test
    void a_filling_queue_raises_the_limit_whatever_the_latency_arithmetic_prefers() {
        // The failure this controller was rewritten for. The limit is also the admission window —
        // a function holds limit + queueSize and refuses the rest — so a controller that sizes it
        // from throughput alone sheds traffic. Refusing is worse than being slow, and the ordering
        // is not negotiable.
        FunctionSpec fn = spec("fn", 16, sojournControl(50, 1, 8));
        // Tiny service time, so Little alone would ask for almost nothing.
        tick(fn, 6, 10, 500, 1.0, 20);

        int underPressure = tick(fn, 6, 95, 500, 1.0, 20);

        assertThat(underPressure).isGreaterThan(6);
    }

    @Test
    void a_quiet_queue_gets_only_what_the_load_needs() {
        // The other half of the same decision: with the buffer doing its job there is no reason to
        // hold concurrency the load is not asking for.
        FunctionSpec fn = spec("fn", 16, sojournControl(50, 1, 8));
        tick(fn, 2, 0, 200, 2.0, 5);

        int quiet = tick(fn, 2, 0, 200, 2.0, 5);

        assertThat(quiet).isLessThanOrEqualTo(4);
    }

    @Test
    void the_limit_follows_littles_law_on_the_load_it_expects() {
        // 1000 requests per second at 4ms each needs about four in flight to sustain, and that is
        // arithmetic rather than a preference.
        FunctionSpec fn = spec("fn", 32, sojournControl(1_000, 1, 32));
        tick(fn, 4, 0, 5_000, 4.0, 10);

        int limit = tick(fn, 4, 0, 5_000, 4.0, 10);

        assertThat(limit).isBetween(3, 6);
    }

    @Test
    void a_falling_load_is_not_credited_to_the_controller() {
        // What sank the previous design: it read a trough as proof that its own last move helped,
        // shrank further, and was still small when the load returned. Nothing here compares two
        // intervals, so a quiet spell lowers the limit only as far as the quiet itself justifies,
        // and the limit rises again as soon as the load does.
        FunctionSpec fn = spec("fn", 16, sojournControl(50, 1, 8));
        tick(fn, 6, 60, 4_000, 3.0, 60);
        int busy = tick(fn, 6, 60, 4_000, 3.0, 60);
        int quiet = tick(fn, 1, 0, 200, 3.0, 5);
        int busyAgain = tick(fn, 6, 60, 4_000, 3.0, 60);

        assertThat(quiet).isLessThan(busy);
        assertThat(busyAgain).isGreaterThanOrEqualTo(busy - 1);
    }

    @Test
    void a_broken_promise_drains_the_backlog_harder() {
        // The wait is the only part of the caller's latency a concurrency limit can shorten, so
        // being outside the promise shortens the horizon the backlog is allowed to take.
        FunctionSpec relaxed = spec("relaxed", 32, sojournControl(1_000, 1, 32));
        FunctionSpec strict = spec("strict", 32, sojournControl(10, 1, 32));
        tick(relaxed, 4, 60, 1_000, 4.0, 100);
        int withRoom = tick(relaxed, 4, 60, 1_000, 4.0, 100);
        // Reset the accumulators so the second function sees the same interval, not a continuation.
        served = 0;
        serviceTotalMs = 0;
        e2eTotalMs = 0;
        tick(strict, 4, 60, 1_000, 4.0, 100);
        int inAHurry = tick(strict, 4, 60, 1_000, 4.0, 100);

        assertThat(inAHurry).isGreaterThan(withRoom);
    }

    @Test
    void an_interval_that_completed_nothing_still_honours_the_admission_guard() {
        // No completions means no service time and no model, but a full queue is still a full
        // queue, and it is exactly the moment when refusing traffic is closest.
        FunctionSpec fn = spec("fn", 16, sojournControl(50, 1, 8));
        tick(fn, 5, 10, 100, 2.0, 20);

        int stalledButFull = tick(fn, 5, 98, 0, 0, 0);

        assertThat(stalledButFull).isGreaterThan(5);
    }

    @Test
    void it_never_leaves_the_window_it_was_given() {
        FunctionSpec fn = spec("fn", 16, sojournControl(10, 3, 5));
        List<Integer> limits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            limits.add(tick(fn, 4, 10 * i, 2_000, 5.0, 20 + i * 20.0));
        }

        assertThat(limits).isNotEmpty().allSatisfy(l -> assertThat(l).isBetween(3, 5));
    }

    @Test
    void the_ceiling_is_the_configured_concurrency_when_no_maximum_is_set() {
        FunctionSpec fn = spec("fn", 2, sojournControl(10, 1, null));
        List<Integer> limits = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            limits.add(tick(fn, 2, 95, 5_000, 8.0, 200));
        }

        assertThat(limits).isNotEmpty().allSatisfy(l -> assertThat(l).isLessThanOrEqualTo(2));
    }

    @Test
    void it_publishes_the_mode_so_the_limit_can_be_attributed() {
        FunctionSpec fn = spec("fn", 16, sojournControl(50, 1, 8));

        tick(fn, 4, 10, 100, 2.0, 20);

        assertThat(metricsSource.modes).containsEntry("fn", ConcurrencyControlMode.SOJOURN);
        assertThat(metricsSource.effective).containsKey("fn");
    }

    @Test
    void a_removed_function_leaves_no_forecast_behind() {
        FunctionSpec fn = spec("fn", 16, sojournControl(50, 1, 8));
        tick(fn, 4, 10, 4_000, 3.0, 30);
        tick(fn, 4, 10, 4_000, 3.0, 30);

        controller.removeFunctionState("fn");
        // The interval trackers are gone too, so the next reading opens a fresh baseline instead of
        // being differenced against a function that no longer exists.
        int reopened = tick(fn, 1, 0, 4_000, 3.0, 30);

        assertThat(reopened).isEqualTo(1);
    }

    private static final class RecordingMetricsSource implements ScalingMetricsSource {
        private final Map<String, Integer> effective = new HashMap<>();
        private final Map<String, ConcurrencyControlMode> modes = new HashMap<>();
        private final Map<String, Integer> queueDepths = new HashMap<>();

        @Override
        public int queueDepth(String functionName) {
            return queueDepths.getOrDefault(functionName, 0);
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
