package it.unimib.datai.nanofaas.execution.admission;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moved from {@code :modules:sync-queue} (Task 10, issue #208) alongside
 * {@link SyncQueueAdmissionController}. Fixture values are built directly from
 * {@link SyncQueueRuntimeDefaults} rather than the module's own {@code SyncQueueProperties} record
 * this test used before the move: {@code execution-runtime} must not depend on an optional
 * module, even in tests, and {@code SyncQueueRuntimeDefaults} (control-plane-spi) is the exact
 * same data the module's {@code SyncQueueProperties.runtimeDefaults()} used to hand to
 * {@link SyncQueueConfigSource#fixed}. Every literal value below is unchanged from the original.
 */
class SyncQueueAdmissionControllerTest {

    @Test
    void rejectsWhenDepthExceeded() {
        int maxDepth = 1;
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(new SyncQueueRuntimeDefaults(
                true, false, Duration.ofSeconds(2), Duration.ofSeconds(2), 2));
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(configSource, maxDepth, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 1, Instant.parse("2026-02-01T00:00:10Z"));

        assertFalse(result.accepted());
        assertEquals(SyncQueueRejectReason.DEPTH, result.reason());
    }

    @Test
    void rejectsWhenEstimatedWaitTooHigh() {
        int maxDepth = 10;
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(new SyncQueueRuntimeDefaults(
                true, true, Duration.ofSeconds(2), Duration.ofSeconds(2), 2));
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(configSource, maxDepth, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 6, now);

        assertFalse(result.accepted());
        assertEquals(SyncQueueRejectReason.EST_WAIT, result.reason());
    }

    @Test
    void acceptsWhenUnderLimits() {
        int maxDepth = 10;
        SyncQueueConfigSource configSource = SyncQueueConfigSource.fixed(new SyncQueueRuntimeDefaults(
                true, true, Duration.ofSeconds(30), Duration.ofSeconds(30), 2));
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(configSource, maxDepth, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 1, now);

        assertTrue(result.accepted());
    }

    /** The limit is compared at sub-second precision: PT0.9S must not truncate to the
     * reject-everything zero, and PT1.9S must not be treated as 1 s. */
    @Test
    void subSecondMaxEstimatedWaitIsNotTruncated() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        for (int i = 0; i < 10; i++) {
            estimator.recordDispatch("fn", now.minusSeconds(9));
        }
        // 10 dispatches in a 10 s window: 1 per second, so depth n estimates n seconds.
        assertTrue(controllerWithMaxWait(Duration.ofMillis(900), estimator).evaluate("fn", 0, now).accepted());
        assertTrue(controllerWithMaxWait(Duration.ofMillis(1900), estimator).evaluate("fn", 1, now).accepted());
        assertFalse(controllerWithMaxWait(Duration.ofMillis(1900), estimator).evaluate("fn", 2, now).accepted());
        SyncQueueAdmissionResult zero = controllerWithMaxWait(Duration.ZERO, estimator).evaluate("fn", 0, now);
        assertFalse(zero.accepted());
        assertEquals(SyncQueueRejectReason.EST_WAIT, zero.reason());
    }

    private static SyncQueueAdmissionController controllerWithMaxWait(Duration maxWait, WaitEstimator estimator) {
        return new SyncQueueAdmissionController(SyncQueueConfigSource.fixed(new SyncQueueRuntimeDefaults(
                true, true, maxWait, Duration.ofSeconds(30), 2)), 10, estimator);
    }
}
