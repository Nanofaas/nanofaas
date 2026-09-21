package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13b (issue #208): the successor of {@code SyncSchedulerMaintenanceTest}. That test drove
 * the retired {@code SyncScheduler} only to make it run the estimator's timed maintenance from
 * its own tick loop; the loop is deleted and {@link SyncQueueConfiguration
 * #syncQueueEstimatorMaintenance} owns that job now, so this drives the real production bean
 * instead. Same property, same assertions: while the bean is alive an idle estimator prunes its
 * expired per-function samples within the configured window, and once the bean is shut down
 * nothing prunes them any more.
 */
class SyncQueueEstimatorMaintenanceTest {

    @Test
    void theMaintenanceBeanPrunesAnIdleEstimatorAndStopsPruningOnceShutDown() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofMillis(100), 1, 10, 10, 1);
        estimator.recordDispatch("idle", Instant.now());
        assertEquals(1, estimator.retentionSnapshot().functionStates());

        ScheduledExecutorService maintenance =
                new SyncQueueConfiguration().syncQueueEstimatorMaintenance(estimator);
        try {
            awaitTrue(() -> estimator.retentionSnapshot().functionStates() == 0,
                    Duration.ofSeconds(2));

            maintenance.shutdownNow();
            estimator.recordDispatch("after-shutdown", Instant.now());
            assertRemainsTrue(() -> estimator.retentionSnapshot().functionStates() == 1,
                    Duration.ofMillis(650));
        } finally {
            maintenance.shutdownNow();
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
