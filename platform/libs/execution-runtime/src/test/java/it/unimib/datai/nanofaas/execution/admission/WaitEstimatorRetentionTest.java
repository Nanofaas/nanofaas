package it.unimib.datai.nanofaas.execution.admission;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitEstimatorRetentionTest {
    @Test
    void maintenanceDropsEveryIdleFunctionAndKeepsActiveOnes() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3, 10);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            estimator.recordDispatch("idle-" + i, start);
        }

        Instant afterIdle = start.plusSeconds(11);
        estimator.recordDispatch("active", afterIdle);
        estimator.recordDispatch("active", afterIdle.plusNanos(1));
        estimator.recordDispatch("active", afterIdle.plusNanos(2));
        assertEquals(6, estimator.retentionSnapshot().functionStates());

        estimator.maintain(afterIdle.plusNanos(2));

        assertEquals(1, estimator.retentionSnapshot().functionStates());
        assertEquals(3, estimator.retentionSnapshot().perFunctionSamples());
    }

    /** Admission only reads: other functions' idle states are left to maintenance. */
    @Test
    void admissionChecksNeverDropStatesAndMaintenanceDoes() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3, 100);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            estimator.recordDispatch("idle-" + i, start);
        }

        Instant afterIdle = start.plusSeconds(11);
        for (int i = 0; i < 3; i++) {
            estimator.estimateWaitSeconds("unknown", 1, afterIdle);
            estimator.estimateWaitSeconds("idle-" + i, 1, afterIdle);
        }
        assertEquals(5, estimator.retentionSnapshot().functionStates());

        estimator.maintain(afterIdle);

        assertEquals(0, estimator.retentionSnapshot().functionStates());
        assertEquals(0, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void anIdleFunctionEstimatesInfinityAndMaintenanceDropsItsState() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 100);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        estimator.recordDispatch("fn", start);

        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("fn", 1, start.plusSeconds(11)), 0.0);
        estimator.maintain(start.plusSeconds(11));
        assertEquals(0, estimator.retentionSnapshot().functionStates());
        assertEquals(0, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void defaultStateCapacityRejectsOnlyThe8193rdLiveFunction() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 8_192; i++) {
            estimator.recordDispatch("live-" + i, now);
        }

        assertEquals(8_192, estimator.retentionSnapshot().functionStates());
        assertEquals(8_192, estimator.retentionSnapshot().perFunctionSamples());
        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("function-8193", 1, now), 0.0);
    }

    /** A full table of live states refuses unknown functions conservatively, and still counts
     * their dispatches globally. */
    @Test
    void fullLiveFunctionCapacityRejectsUnknownFunctionConservatively() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3, 5);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 20; i++) {
            estimator.recordDispatch("fn-" + i, now);
        }

        assertEquals(5, estimator.retentionSnapshot().functionStates());
        assertEquals(5, estimator.retentionSnapshot().perFunctionSamples());
        assertEquals(20, estimator.retentionSnapshot().globalSamples());
        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("not-retained", 5, now), 0.0);
    }

    @Test
    void overflowEvictsAnIdleStateBeforeUsingLiveGlobalHistory() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 3);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        estimator.recordDispatch("expired", start);
        estimator.recordDispatch("live-a", start.plusSeconds(11));
        estimator.recordDispatch("live-b", start.plusSeconds(11));

        double wait = estimator.estimateWaitSeconds("recovered", 1, start.plusSeconds(11).plusNanos(1));

        assertEquals(5.0, wait, 0.0);
        assertEquals(2, estimator.retentionSnapshot().functionStates());
    }

    @Test
    void expiryAndRemovalFreeStateSlots() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 3);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            estimator.recordDispatch("expired", start.plusNanos(i));
        }
        Instant live = start.plusSeconds(11);
        estimator.recordDispatch("live-a", live);
        estimator.recordDispatch("live-b", live);

        estimator.recordDispatch("after-expiry", live.plusNanos(1));
        estimator.recordDispatch("after-expiry", live.plusNanos(2));
        estimator.recordDispatch("after-expiry", live.plusNanos(3));
        assertEquals(3, estimator.retentionSnapshot().functionStates());
        assertEquals(5, estimator.retentionSnapshot().perFunctionSamples());

        estimator.removeFunctionState("after-expiry");
        estimator.recordDispatch("after-removal", live.plusNanos(4));
        estimator.recordDispatch("after-removal", live.plusNanos(5));
        estimator.recordDispatch("after-removal", live.plusNanos(6));

        assertEquals(3, estimator.retentionSnapshot().functionStates());
        assertEquals(5, estimator.retentionSnapshot().perFunctionSamples());
        assertTrue(Double.isFinite(estimator.estimateWaitSeconds(
                "after-removal", 1, live.plusNanos(6))));
    }

    @Test
    void anOlderClockReadingCannotReorderOrExpireNewerSamples() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 2, 5);
        Instant newest = Instant.parse("2026-09-10T10:00:10Z");
        estimator.recordDispatch("fn", newest);
        estimator.recordDispatch("fn", newest.minusSeconds(20));

        assertEquals(10.0, estimator.estimateWaitSeconds("fn", 2, newest.minusSeconds(30)), 0.0);
        assertEquals(2, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void instantRangeBoundariesDoNotOverflowCutoffCalculation() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 10);
        estimator.recordDispatch("fn", Instant.MIN);
        assertEquals(10.0, estimator.estimateWaitSeconds("fn", 1, Instant.MIN), 0.0);

        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("fn", 1, Instant.MAX), 0.0);
    }

    @Test
    void removalDropsFunctionStateImmediately() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 10);
        estimator.recordDispatch("fn", Instant.parse("2026-09-10T10:00:00Z"));

        estimator.removeFunctionState("fn");

        assertEquals(0, estimator.retentionSnapshot().functionStates());
        assertEquals(0, estimator.retentionSnapshot().perFunctionSamples());
    }
}
