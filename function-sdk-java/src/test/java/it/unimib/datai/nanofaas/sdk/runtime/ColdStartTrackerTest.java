package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColdStartTrackerTest {

    @Test
    void firstInvocation_returnsTrueOnlyOnce() {
        ColdStartTracker tracker = new ColdStartTracker();

        assertTrue(tracker.firstInvocation());
        assertFalse(tracker.firstInvocation());
        assertFalse(tracker.firstInvocation());
    }

    @Test
    void initDurationMs_isNonNegative() {
        ColdStartTracker tracker = new ColdStartTracker();

        assertTrue(tracker.initDurationMs() >= 0);
    }
}
