package it.unimib.datai.mcfaas.controlplane.sync;

import it.unimib.datai.mcfaas.controlplane.config.SyncQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueueAdmissionControllerTest {
    @Test
    void rejectsWhenDepthExceeded() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 1, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(props, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 1, Instant.parse("2026-02-01T00:00:10Z"));

        assertFalse(result.accepted());
        assertEquals(SyncQueueRejectReason.DEPTH, result.reason());
    }

    @Test
    void rejectsWhenEstimatedWaitTooHigh() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(props, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 6, now);

        assertFalse(result.accepted());
        assertEquals(SyncQueueRejectReason.EST_WAIT, result.reason());
    }

    @Test
    void acceptsWhenUnderLimits() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(30), Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 3
        );
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(props, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 1, now);

        assertTrue(result.accepted());
    }
}
