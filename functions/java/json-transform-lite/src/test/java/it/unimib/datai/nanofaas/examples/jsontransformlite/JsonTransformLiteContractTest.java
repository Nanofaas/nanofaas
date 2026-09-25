package it.unimib.datai.nanofaas.examples.jsontransformlite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JsonTransformLiteContractTest {
    @Test
    @ResourceLock(Resources.LOCALE)
    @SuppressWarnings("unchecked")
    void operationNameIsIndependentFromDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Method handle = JsonTransformLite.class.getDeclaredMethod("handle", Object.class);
            handle.setAccessible(true);
            Map<String, Object> actual = (Map<String, Object>) handle.invoke(null, Map.of(
                    "data", List.of(Map.of("department", "engineering", "salary", 42)),
                    "groupBy", "department",
                    "operation", "MIN",
                    "valueField", "salary"));
            Map<String, Object> groups = (Map<String, Object>) actual.get("groups");

            assertEquals(42.0, ((Number) groups.get("engineering")).doubleValue());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "test-data", "json-transform", "correctness.json").toFile()).get("cases");
        Method handle = JsonTransformLite.class.getDeclaredMethod("handle", Object.class);
        handle.setAccessible(true);
        for (JsonNode contractCase : cases) {
            String name = contractCase.get("name").asText();
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = unwrap(handle.invoke(null, input), contractCase, name);
            JsonNode expected = contractCase.get("expected");
            if (expected.has("error")) {
                assertEquals(expected.get("error").asText(), actual.get("error"), name);
            } else {
                assertEquals(expected.get("groupBy").asText(), actual.get("groupBy"), name);
                assertEquals(expected.get("operation").asText(), actual.get("operation"), name);
                Map<String, Object> groups = (Map<String, Object>) actual.get("groups");
                expected.get("groups").fields().forEachRemaining(entry ->
                        assertEquals(entry.getValue().asDouble(), ((Number) groups.get(entry.getKey())).doubleValue(), name));
            }
        }
    }

    /** The shared corpus pins the status too: a 200 case is a plain value, any other an envelope. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrap(Object result, JsonNode contractCase, String name) {
        int expectedStatus = contractCase.has("expectedStatusCode") ? contractCase.get("expectedStatusCode").asInt() : 200;
        if (expectedStatus == 200) {
            assertFalse(result instanceof HandlerResponse, name + ": a 200 case must return a plain value");
            return (Map<String, Object>) result;
        }
        HandlerResponse response = assertInstanceOf(HandlerResponse.class, result, name + ": expected an envelope");
        assertEquals(expectedStatus, response.statusCode(), name + ": status code");
        return (Map<String, Object>) response.output();
    }
}
