package it.unimib.datai.nanofaas.controlplane.api;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorResponsesTest {
    @Test
    void validationDetailsCannotBeChangedAfterPublication() {
        List<String> details = new ArrayList<>(List.of("name is required"));
        var body = ApiErrorResponses.validationBody(details);
        details.clear();
        assertEquals(List.of("error", "message", "details"), List.copyOf(body.keySet()));
        assertEquals(List.of("name is required"), body.get("details"));
        assertThrows(UnsupportedOperationException.class, () -> body.put("error", "other"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) body.get("details")).clear());
    }
}
