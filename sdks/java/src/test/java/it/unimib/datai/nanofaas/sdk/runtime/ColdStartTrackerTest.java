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

    @SuppressWarnings("java:S2925") // simulated handler execution: initDurationMs must stay frozen at request arrival while the handler runs
    @Test
    void initDurationMs_doesNotIncludeHandlerTime() throws Exception {
        ColdStartTracker tracker = new ColdStartTracker();
        tracker.markFirstRequestArrival();

        long durationAtMark = tracker.initDurationMs();

        Thread.sleep(50); // simula esecuzione handler

        // The value must be frozen: it must not change after the mark
        long durationAfterHandler = tracker.initDurationMs();
        assertEquals(durationAtMark, durationAfterHandler,
                "initDurationMs should be frozen at request arrival, not grow with handler time");
        assertTrue(durationAtMark >= 0, "initDurationMs must not be negative");
    }

    @Test
    void initDurationMs_beforeMarkFirstRequestArrival_returnsMinusOne() {
        ColdStartTracker tracker = new ColdStartTracker();
        assertEquals(-1L, tracker.initDurationMs(),
                "initDurationMs should return -1 before markFirstRequestArrival is called");
    }

    @SuppressWarnings("java:S2925") // deliberate delay between mark calls: a second markFirstRequestArrival after 20ms must not change the frozen timestamp
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
