package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitEstimatorRetentionTest {
    @Test
    void boundedMaintenanceEventuallyRemovesEveryInactiveFunction() {
        WaitEstimator estimator = new WaitEstimator(
                Duration.ofSeconds(10), 3, 100, 10, 10, 100, 2);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            estimator.recordDispatch("idle-" + i, start);
        }

        Instant afterIdle = start.plusSeconds(11);
        estimator.recordDispatch("active", afterIdle);
        assertTrue(estimator.retentionSnapshot().functionStates() >= 1);
        assertTrue(estimator.retentionSnapshot().functionStates() <= 4);

        estimator.recordDispatch("active", afterIdle.plusNanos(1));
        estimator.recordDispatch("active", afterIdle.plusNanos(2));

        assertEquals(1, estimator.retentionSnapshot().functionStates());
        assertEquals(3, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void admissionChecksAlsoDrainInactiveHistoryAfterIdle() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3, 100, 10, 2);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            estimator.recordDispatch("idle-" + i, start);
        }

        Instant afterIdle = start.plusSeconds(11);
        for (int i = 0; i < 3; i++) {
            estimator.estimateWaitSeconds("unknown", 1, afterIdle);
        }

        assertEquals(0, estimator.retentionSnapshot().functionStates());
        assertEquals(0, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void checkingAnIdleFunctionRemovesItsOwnExpiredState() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 100, 10, 2);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        estimator.recordDispatch("fn", start);

        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("fn", 1, start.plusSeconds(11)), 0.0);
        assertEquals(0, estimator.retentionSnapshot().functionStates());
        assertEquals(0, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void capsExactHistoryWhileKeepingTheNewestSamples() {
        WaitEstimator estimator = new WaitEstimator(
                Duration.ofSeconds(10), 3, 5, 3, 5, 7, 1);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 20; i++) {
            estimator.recordDispatch("fn", start.plusMillis(i));
        }

        WaitEstimator.RetentionSnapshot retained = estimator.retentionSnapshot();
        assertEquals(5, retained.globalSamples());
        assertEquals(3, retained.perFunctionSamples());
        assertEquals(20.0, estimator.estimateWaitSeconds("fn", 6, start.plusSeconds(1)), 0.0);
    }

    @Test
    void fewHotFunctionsCannotConsumeUnusedFunctionHistorySlots() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 5, 5, 1);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 20; i++) {
            estimator.recordDispatch("hot-a", now.plusNanos(i));
            estimator.recordDispatch("hot-b", now.plusNanos(i));
        }

        assertEquals(2, estimator.retentionSnapshot().functionStates());
        assertEquals(2, estimator.retentionSnapshot().perFunctionSamples());
        assertTrue(Double.isFinite(estimator.estimateWaitSeconds("unused", 1, now)));

        estimator.recordDispatch("unused", now);

        assertEquals(3, estimator.retentionSnapshot().functionStates());
        assertEquals(3, estimator.retentionSnapshot().perFunctionSamples());
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

    @Test
    void fullLiveFunctionCapacityRejectsUnknownFunctionConservatively() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3, 5, 3, 1);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 20; i++) {
            estimator.recordDispatch("fn-" + i, now);
        }

        assertTrue(estimator.retentionSnapshot().functionStates() <= 5);
        assertTrue(estimator.retentionSnapshot().perFunctionSamples() <= 5);
        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("not-retained", 5, now), 0.0);
    }

    @Test
    void overflowEvictsExpiredStateBeforeUsingLiveGlobalHistory() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 3, 3, 1);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        estimator.recordDispatch("expired", start);
        estimator.recordDispatch("live-a", start.plusSeconds(10));
        estimator.recordDispatch("live-b", start.plusSeconds(10));

        double wait = estimator.estimateWaitSeconds(
                "recovered", 1, start.plusSeconds(10).plusNanos(1));

        assertEquals(5.0, wait, 0.0);
        assertEquals(2, estimator.retentionSnapshot().functionStates());
    }

    @Test
    void expiryAndRemovalRestoreReservedFunctionHistorySlots() {
        WaitEstimator estimator = new WaitEstimator(
                Duration.ofSeconds(10), 1, 5, 5, 3, 5, 1);
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
    void maintenanceGivesGlobalAndFunctionHistoryIndependentBoundedShares() {
        WaitEstimator estimator = new WaitEstimator(
                Duration.ofSeconds(10), 1, 20, 10, 5, 20, 2, 2);
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 6; i++) {
            estimator.recordDispatch("a", start);
            estimator.recordDispatch("b", start);
        }

        estimator.maintain(start.plusSeconds(11));

        WaitEstimator.RetentionSnapshot retained = estimator.retentionSnapshot();
        assertEquals(10, retained.globalSamples());
        assertEquals(10, retained.perFunctionSamples());
    }

    @Test
    void maintenanceRotationKeepsOneBoundedCandidatePerFunction() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 5, 5, 1);
        Instant now = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < 100; i++) {
            estimator.recordDispatch("same", now.plusNanos(i));
            estimator.maintain(now.plusNanos(i));
        }

        assertEquals(1, estimator.retentionSnapshot().functionStates());
        assertEquals(1, estimator.retentionSnapshot().cleanupCandidates());
    }

    @Test
    void anOlderClockReadingCannotReorderOrExpireNewerSamples() {
        WaitEstimator estimator = new WaitEstimator(
                Duration.ofSeconds(10), 2, 10, 10, 5, 10, 1);
        Instant newest = Instant.parse("2026-09-10T10:00:10Z");
        estimator.recordDispatch("fn", newest);
        estimator.recordDispatch("fn", newest.minusSeconds(20));

        assertEquals(10.0, estimator.estimateWaitSeconds("fn", 2, newest.minusSeconds(30)), 0.0);
        assertEquals(2, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void instantRangeBoundariesDoNotOverflowCutoffCalculation() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 10, 10, 1);
        estimator.recordDispatch("fn", Instant.MIN);
        assertEquals(10.0, estimator.estimateWaitSeconds("fn", 1, Instant.MIN), 0.0);

        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("fn", 1, Instant.MAX), 0.0);
    }

    @Test
    void exactWindowIncludesTheCutoffAndExcludesAnythingOlder() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 10, 10, 1);
        Instant now = Instant.parse("2026-09-10T10:00:10Z");
        estimator.recordDispatch("fn", now.minus(Duration.ofSeconds(10).plusNanos(1)));
        estimator.recordDispatch("fn", now.minusSeconds(10));

        assertEquals(10.0, estimator.estimateWaitSeconds("fn", 1, now), 0.0);
        assertEquals(1, estimator.retentionSnapshot().perFunctionSamples());
    }

    @Test
    void removalDropsCappedFunctionHistoryImmediately() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1, 10, 10, 1);
        estimator.recordDispatch("fn", Instant.parse("2026-09-10T10:00:00Z"));

        estimator.removeFunctionState("fn");

        assertEquals(0, estimator.retentionSnapshot().functionStates());
        assertEquals(0, estimator.retentionSnapshot().perFunctionSamples());
    }
}
