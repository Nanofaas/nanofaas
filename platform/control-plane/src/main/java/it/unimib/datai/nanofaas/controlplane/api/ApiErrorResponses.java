package it.unimib.datai.nanofaas.controlplane.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/** Ordered control-plane HTTP error bodies, also serializable in native artifacts. */
final class ApiErrorResponses {
    private ApiErrorResponses() {}

    static Map<String, Object> body(String code, String message) {
        SequencedMap<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return Collections.unmodifiableSequencedMap(body);
    }

    static Map<String, Object> validationBody(List<String> details) {
        SequencedMap<String, Object> body = new LinkedHashMap<>(body(
                "VALIDATION_ERROR", "Request validation failed"));
        body.put("details", List.copyOf(details));
        return Collections.unmodifiableSequencedMap(body);
    }
}
