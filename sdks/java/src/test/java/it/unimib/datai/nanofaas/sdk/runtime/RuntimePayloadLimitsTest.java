package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePayloadLimitsTest {
    @Test
    void measuresSerializedOutputAgainstFiniteLimit() {
        RuntimePayloadLimits limits = new RuntimePayloadLimits(JsonMapper.builder().build(), 16);
        assertFalse(limits.outputTooLarge(Map.of("x", "ok")));
        assertTrue(limits.outputTooLarge(Map.of("x", "01234567890123456789")));
    }

    @Test
    void rejectsNonPositiveLimit() {
        var mapper = JsonMapper.builder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePayloadLimits(mapper, 0));
    }
}
