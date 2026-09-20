package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SyncSchedulerMaintenanceTest {
    @Test
    void emptyQueueRunsTimedMaintenanceAndLifecycleStopEndsIt() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofMillis(100), 1, 10, 10, 1);
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2,
                Duration.ofMillis(100), 1);
        SyncQueueService queue = new SyncQueueService(
                props, new ExecutionStore(), estimator,
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()),
                new FunctionCapacityRegistry(), null);
        estimator.recordDispatch("idle", Instant.now());
        assertEquals(0, queue.queuedItems());

        SyncScheduler scheduler = new SyncScheduler(
                mock(QueuedDispatchCapacity.class), queue, mock(InvocationDispatch.class), org.mockito.Mockito.mock(it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle.class));
        try {
            scheduler.start();
            awaitTrue(() -> estimator.retentionSnapshot().functionStates() == 0,
                    Duration.ofSeconds(2));

            scheduler.stop();
            estimator.recordDispatch("after-stop", Instant.now());
            assertRemainsTrue(() -> estimator.retentionSnapshot().functionStates() == 1,
                    Duration.ofMillis(650));
        } finally {
            scheduler.stop();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void assertRemainsTrue(BooleanSupplier condition, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(condition.getAsBoolean());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }
}
