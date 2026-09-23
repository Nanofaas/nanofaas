package it.unimib.datai.nanofaas.controlplane;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11 (issue #208): a single engine-backed {@link WorkloadMetricsSource}/
 * {@link WorkloadMetricsBinder}, the {@code scheduler_active} gauges and the switch
 * outcome/duration meters, all with cardinality bounded by the artefact's own built-in
 * strategies — never by execution id, ticket id or generation.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class EngineWorkloadMetricsTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry registry;

    @Test
    void publishesExactlyOneWorkloadMetricsSourceAndBinderWhenAQueueModuleIsPresent() {
        int expected = context.getBeansOfType(SchedulingStrategy.class).isEmpty() ? 0 : 1;
        assertThat(context.getBeansOfType(WorkloadMetricsSource.class)).hasSize(expected);
        assertThat(context.getBeansOfType(WorkloadMetricsBinder.class)).hasSize(expected);
    }

    @Test
    void schedulerActiveGaugesAreBoundedByTheBuiltInStrategiesAndReflectTheActiveOne() {
        if (context.getBeansOfType(SchedulingStrategy.class).isEmpty()) {
            return; // No engine on this profile: nothing to assert.
        }
        SchedulerEngine engine = context.getBean(SchedulerEngine.class);
        int strategyCount = context.getBeansOfType(SchedulingStrategy.class).size();

        assertThat(registry.find("scheduler_active").gauges()).hasSize(strategyCount);
        String active = engine.snapshot().strategy();
        assertThat(registry.find("scheduler_active").tag("strategy", active).gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void aSwitchNeverDuplicatesThePendingPopulationAndRecordsExactlyOneOutcome() {
        if (context.getBeansOfType(SchedulingStrategy.class).size() < 2) {
            return; // Both strategies must be present for a real switch to be exercised.
        }
        SchedulerEngine engine = context.getBean(SchedulerEngine.class);
        String started = engine.snapshot().strategy();
        String other = engine.snapshot().available().stream()
                .filter(id -> !id.equals(engine.snapshot().strategy()))
                .findFirst().orElseThrow();
        var before = engine.snapshotQueues();
        double switchesBefore = registry.find("scheduler_switch_total").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();

        engine.switchTo(other);

        assertThat(engine.snapshotQueues().pending()).isEqualTo(before.pending());
        double switchesAfter = registry.find("scheduler_switch_total").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        assertThat(switchesAfter).isEqualTo(switchesBefore + 1);
        assertThat(registry.find("scheduler_active").tag("strategy", other).gauge().value())
                .isEqualTo(1.0);

        // Put the selection back: this application context is cached and shared with every other
        // test in the class, so leaving the engine switched to `other` makes whatever runs next
        // depend on whether this test ran first. Asserted rather than merely done, so a failed
        // restore cannot pass unnoticed.
        engine.switchTo(started);
        assertThat(engine.snapshot().strategy()).isEqualTo(started);
    }
}
