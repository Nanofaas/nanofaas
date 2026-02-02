package it.unimib.datai.mcfaas.controlplane.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaitEstimatorTest {
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
}
