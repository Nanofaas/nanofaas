package it.unimib.datai.nanofaas.sdk.lite.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeLimitsTest {
    @Test
    void callbackReservationPrecedesHandlerAndBothReleaseExactlyOnce() {
        RuntimeLimits limits = new RuntimeLimits(1, 1, 64, 32, 32, 64);

        var callback = limits.tryReserveCallback();
        assertNotNull(callback);
        assertNull(limits.tryReserveCallback());
        var handler = limits.tryReserveHandler();
        assertNotNull(handler);
        assertNull(limits.tryReserveHandler());
        assertEquals(1, limits.pendingCallbacks());
        assertEquals(64, limits.pendingCallbackBytes());

        handler.close();
        callback.close();
        handler.close();
        callback.close();

        assertEquals(0, limits.activeHandlers());
        assertEquals(0, limits.pendingCallbacks());
        assertEquals(0, limits.pendingCallbackBytes());
    }

    /**
     * Admission reserves the largest callback payload, the output being unknown; held until
     * delivery, that capped pending callbacks at the byte budget / maximum payload (8 with the
     * defaults) instead of the configured count.
     */
    @Test
    void aSerializedCallbackHoldsOnlyItsOwnSize() {
        RuntimeLimits limits = new RuntimeLimits(4, 8, 128, 32, 32, 64);
        var first = limits.tryReserveCallback();
        var second = limits.tryReserveCallback();
        assertNull(limits.tryReserveCallback(), "two maxima fill the 128-byte budget");

        first.shrinkTo(2);
        second.shrinkTo(2);

        assertEquals(4, limits.pendingCallbackBytes());
        var third = limits.tryReserveCallback();
        assertNotNull(third, "the returned bytes admit a third callback");

        first.close();
        first.shrinkTo(1);
        second.close();
        third.close();
        assertEquals(0, limits.pendingCallbacks());
        assertEquals(0, limits.pendingCallbackBytes(), "a shrink after release changes nothing");
    }

    @Test
    void rejectsInvalidOrInconsistentLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(1, 1, 63, 1, 1, 64));
    }
}
