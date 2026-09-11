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

    @Test
    void rejectsInvalidOrInconsistentLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(1, 1, 63, 1, 1, 64));
    }
}
