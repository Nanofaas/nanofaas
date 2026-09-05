package it.unimib.datai.nanofaas.modules.autoscaler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScalingProgressTrackerTest {

    private static final Instant START = Instant.parse("2026-03-06T10:00:00Z");

    private ScalingProgressTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ScalingProgressTracker();
    }

    @Test
    void rolloutCaughtUp_isNotStuckAndClearsState() {
        assertThat(tracker.isStuck("fn", 10, 10, START)).isFalse();
        // After the rollout completes, a later regression in ready does not reuse stale state.
        assertThat(tracker.isStuck("fn", 10, 2, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1)))
                .isFalse();
    }

    @Test
    void readyAdvancing_towardTarget_isNeverStuck() {
        assertThat(tracker.isStuck("fn", 10, 2, START)).isFalse();
        // Ready advanced from 2 to 3: progress, even though still far from 10.
        assertThat(tracker.isStuck("fn", 10, 3, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1)))
                .isFalse();
    }

    @Test
    void noProgressWithinWindow_isNotYetStuck() {
        assertThat(tracker.isStuck("fn", 10, 2, START)).isFalse();
        assertThat(tracker.isStuck("fn", 10, 2, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS - 1)))
                .isFalse();
    }

    @Test
    void noProgressBeyondWindow_isStuck() {
        assertThat(tracker.isStuck("fn", 10, 2, START)).isFalse();
        assertThat(tracker.isStuck("fn", 10, 2, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS)))
                .isTrue();
        assertThat(tracker.isStuck("fn", 10, 2, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1)))
                .isTrue();
    }

    @Test
    void newRequestedTarget_resetsTheWindow() {
        assertThat(tracker.isStuck("fn", 10, 2, START)).isFalse();
        // A new (higher) target is commanded: the observation restarts, so it is not stuck yet.
        assertThat(tracker.isStuck("fn", 12, 2, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1)))
                .isFalse();
    }

    @Test
    void clear_removesState() {
        assertThat(tracker.isStuck("fn", 10, 2, START)).isFalse();
        tracker.clear("fn");
        assertThat(tracker.isStuck("fn", 10, 2, START.plusMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1)))
                .isFalse();
    }
}
