package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class InvocationObservationsTest {
    @Test void readsNeverCreateOrResurrectMetersAndSnapshotsRetainOnlyValues() {
        var registry = new SimpleMeterRegistry();
        var capacity = new FunctionCapacityRegistry();
        var metrics = new Metrics(registry, capacity);
        InvocationObservations observations = metrics;
        assertThat(observations.snapshot("fn")).isEqualTo(InvocationObservations.Snapshot.absent());
        assertThat(registry.getMeters()).isEmpty();

        var generation = capacity.register("fn", 1).generation();
        metrics.registerFunction("fn");
        metrics.dispatch("fn");
        metrics.latency("fn").record(Duration.ofMillis(7));
        metrics.e2eLatency("fn").record(Duration.ofMillis(19));
        var original = observations.snapshot("fn");
        assertThat(original.generation()).isEqualTo(generation);
        assertThat(original.service()).isEqualTo(new InvocationObservations.DurationTotals(1, 7));
        assertThat(original.endToEnd()).isEqualTo(new InvocationObservations.DurationTotals(1, 19));

        capacity.remove("fn");
        metrics.removeFunction("fn");
        for (int i = 0; i < 100; i++) {
            assertThat(observations.snapshot("fn")).isEqualTo(InvocationObservations.Snapshot.absent());
        }
        assertThat(registry.getMeters()).isEmpty();
        capacity.register("fn", 1);
        metrics.registerFunction("fn");
        assertThat(observations.snapshot("fn").generation()).isNotEqualTo(generation);
        assertThat(observations.snapshot("fn").dispatched()).isZero();
        assertThat(original.dispatched()).isEqualTo(1);
    }
}
