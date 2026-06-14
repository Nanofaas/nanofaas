package it.unimib.datai.nanofaas.modules.autoscaler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColdStartTrackerTest {

    @Test
    void isPotentialColdStart_returnsFalse_whenNoScaleUpRecorded() {
        ColdStartTracker tracker = new ColdStartTracker();
        assertThat(tracker.isPotentialColdStart("myFunc")).isFalse();
    }

    @Test
    void isPotentialColdStart_returnsTrue_afterScaleUpFromZero() {
        ColdStartTracker tracker = new ColdStartTracker();
        tracker.recordScaleUp("myFunc", 0, 1);
        assertThat(tracker.isPotentialColdStart("myFunc")).isTrue();
    }

    @Test
    void isPotentialColdStart_returnsFalse_afterScaleUpFromNonZero() {
        ColdStartTracker tracker = new ColdStartTracker();
        tracker.recordScaleUp("myFunc", 1, 3);
        assertThat(tracker.isPotentialColdStart("myFunc")).isFalse();
    }

    @Test
    void isPotentialColdStart_differentFunctions_areIndependent() {
        ColdStartTracker tracker = new ColdStartTracker();
        tracker.recordScaleUp("funcA", 0, 1);
        assertThat(tracker.isPotentialColdStart("funcA")).isTrue();
        assertThat(tracker.isPotentialColdStart("funcB")).isFalse();
    }
}
