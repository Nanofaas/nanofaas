package it.unimib.datai.nanofaas.controlplane.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitEstimatorTest {
    @Test
    void returnsZeroWhenQueueDepthIsZeroEvenWithoutSamples() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");

        double est = estimator.estimateWaitSeconds("fn", 0, now);

        assertEquals(0.0, est, 0.0);
    }

    @Test
    void usesPerFunctionWhenEnoughSamples() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));

        double est = estimator.estimateWaitSeconds("fn", 6, now);

        assertEquals(20.0, est, 0.01);
    }

    @Test
    void fallsBackToGlobalWhenSamplesInsufficient() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("other", now.minusSeconds(9));
        estimator.recordDispatch("other", now.minusSeconds(8));
        estimator.recordDispatch("other", now.minusSeconds(7));
        estimator.recordDispatch("fn", now.minusSeconds(9));

        double est = estimator.estimateWaitSeconds("fn", 3, now);

        assertEquals(7.5, est, 0.01);
    }

    @Test
    void estimateWaitSeconds_prunesPerFunctionDequeOnlyOncePerEvaluation() {
        TrackingDeque globalEvents = new TrackingDeque();
        TrackingDeque functionEvents = new TrackingDeque();
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        functionEvents.addLast(now.minusSeconds(9));
        functionEvents.addLast(now.minusSeconds(8));
        functionEvents.addLast(now.minusSeconds(7));

        WaitEstimator estimator = new WaitEstimator(
                Duration.ofSeconds(10),
                3,
                globalEvents,
                Map.of("fn", functionEvents)
        );

        double est = estimator.estimateWaitSeconds("fn", 6, now);

        assertEquals(20.0, est, 0.01);
        assertEquals(1, functionEvents.peekFirstCount);
        assertTrue(functionEvents.pollFirstCount <= 1);
    }

    private static final class TrackingDeque extends ArrayDeque<Instant> {
        private int peekFirstCount;
        private int pollFirstCount;

        @Override
        public Instant peekFirst() {
            peekFirstCount++;
            return super.peekFirst();
        }

        @Override
        public Instant pollFirst() {
            pollFirstCount++;
            return super.pollFirst();
        }
    }
}
