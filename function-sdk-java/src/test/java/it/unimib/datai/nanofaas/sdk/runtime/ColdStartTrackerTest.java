package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        tracker.markFirstRequestArrival();

        assertTrue(tracker.initDurationMs() >= 0);
    }

    @Test
    void initDurationMs_doesNotIncludeHandlerTime() throws Exception {
        ColdStartTracker tracker = new ColdStartTracker();
        Thread.sleep(10); // simula tempo di startup Spring

        tracker.markFirstRequestArrival();

        Thread.sleep(50); // simula esecuzione handler

        long duration = tracker.initDurationMs();
        // deve essere >= 10ms (startup simulato) ma NON includere i 50ms dell'handler
        assertTrue(duration >= 0, "initDurationMs must not be negative: " + duration);
        assertTrue(duration < 40,
                "initDurationMs should not include handler time, but was: " + duration + "ms");
    }

    @Test
    void initDurationMs_beforeMarkFirstRequestArrival_returnsMinusOne() {
        ColdStartTracker tracker = new ColdStartTracker();
        assertEquals(-1L, tracker.initDurationMs(),
                "initDurationMs should return -1 before markFirstRequestArrival is called");
    }

    @Test
    void markFirstRequestArrival_isIdempotent() throws Exception {
        ColdStartTracker tracker = new ColdStartTracker();
        tracker.markFirstRequestArrival();
        long first = tracker.initDurationMs();
        Thread.sleep(20);
        tracker.markFirstRequestArrival(); // seconda chiamata, non deve cambiare il timestamp
        long second = tracker.initDurationMs();
        assertEquals(first, second, "markFirstRequestArrival should be idempotent");
    }
}
