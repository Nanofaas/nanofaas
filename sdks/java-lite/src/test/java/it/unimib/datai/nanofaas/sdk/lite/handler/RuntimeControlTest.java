package it.unimib.datai.nanofaas.sdk.lite.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeControlTest {
    @Test
    void corpusDeadlineAndAttemptControlsAreRetained() {
        RuntimeLimits limits = new RuntimeLimits(1, 1, 1_024, 1_024, 1_024, 1_024,
                50, 50, 3, 100);

        assertEquals(50, limits.bodyReadTimeoutMs);
        assertEquals(50, limits.callbackAttemptTimeoutMs);
        assertEquals(3, limits.callbackMaxAttempts);
        assertEquals(100, limits.shutdownTimeoutMs);
    }
}
