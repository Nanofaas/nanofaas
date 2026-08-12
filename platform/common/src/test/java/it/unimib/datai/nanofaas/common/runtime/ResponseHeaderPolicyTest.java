package it.unimib.datai.nanofaas.common.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResponseHeaderPolicyTest {
    @Test
    void isStatusCodeValid_rangeBoundaries() {
        assertTrue(ResponseHeaderPolicy.isStatusCodeValid(200));
        assertTrue(ResponseHeaderPolicy.isStatusCodeValid(599));
        assertTrue(ResponseHeaderPolicy.isStatusCodeValid(503));
        assertFalse(ResponseHeaderPolicy.isStatusCodeValid(199));
        assertFalse(ResponseHeaderPolicy.isStatusCodeValid(600));
        assertFalse(ResponseHeaderPolicy.isStatusCodeValid(999));
    }

    @Test
    void filterAllowedHeaders_keepsAllowedDropsEverythingElse() {
        Map<String, String> raw = Map.of(
                "Content-Type", "application/pdf",
                "X-Execution-Id", "spoof-attempt",
                "X-Cold-Start", "true",
                "Location", "/somewhere",
                "X-Random-Custom", "nope");
        Map<String, String> filtered = ResponseHeaderPolicy.filterAllowedHeaders(raw);
        assertEquals(Map.of("Content-Type", "application/pdf", "Location", "/somewhere"), filtered);
    }

    @Test
    void filterAllowedHeaders_isCaseInsensitiveOnKeys() {
        Map<String, String> filtered = ResponseHeaderPolicy.filterAllowedHeaders(Map.of("content-type", "text/plain"));
        assertEquals(Map.of("content-type", "text/plain"), filtered);
    }

    @Test
    void filterAllowedHeaders_nullInputReturnsEmptyMap() {
        assertTrue(ResponseHeaderPolicy.filterAllowedHeaders(null).isEmpty());
    }
}
