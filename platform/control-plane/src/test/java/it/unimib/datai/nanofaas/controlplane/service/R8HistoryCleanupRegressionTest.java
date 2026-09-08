package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for finding R8 of the 2026-09-08 pre-soak review.
 *
 * <p>Removal leaves historical-name state indefinitely: {@link Metrics} keeps removed
 * function names in {@code removedFunctions} (reduced only when the exact same name is
 * registered again), and {@link ReplicaStatusSnapshot#invalidate} clears an entry's
 * contents but never removes the entry from its map. Both collections therefore grow
 * with every distinct name ever seen rather than with the current function count.
 *
 * <p>Correct behavior (plan tasks P09/P10, invariant I7): state for a removed function
 * is retired once the resources that use it have drained; historical names do not
 * accumulate after removal.
 *
 * <p>These tests assert the desired behavior, so they are RED on the current baseline,
 * where 1,000 removed metric names and 1,000 invalidated replica entries remain.
 */
class R8HistoryCleanupRegressionTest {

    private static int privateSize(Object object, String field) throws Exception {
        Field f = object.getClass().getDeclaredField(field);
        f.setAccessible(true);
        Object value = f.get(object);
        return value instanceof Map<?, ?> map ? map.size() : ((Collection<?>) value).size();
    }

    @Test
    void removedFunctionNamesDoNotAccumulateAfterChurn() throws Exception {
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        for (int i = 0; i < 1000; i++) {
            String name = "deleted-" + i;
            metrics.registerFunction(name);
            metrics.removeFunction(name);
        }
        assertThat(privateSize(metrics, "removedFunctions"))
                .as("removed function names must not accumulate beyond the live functions")
                .isZero();
    }

    @Test
    void invalidatedReplicaTargetsAreRemovedNotJustCleared() throws Exception {
        ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(
                InstantSource.system(), Duration.ofSeconds(5), Runnable::run);
        for (int i = 0; i < 1000; i++) {
            String name = "deleted-" + i;
            snapshot.read(new ManagedDeploymentTarget(name, "container-local"),
                    target -> new ReplicaStatus(1, 1));
            snapshot.invalidate(name);
        }
        assertThat(privateSize(snapshot, "entries"))
                .as("invalidated replica entries must not accumulate after removal")
                .isZero();
    }
}
