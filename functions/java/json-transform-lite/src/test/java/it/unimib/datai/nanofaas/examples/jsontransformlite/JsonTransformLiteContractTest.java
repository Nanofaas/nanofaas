package it.unimib.datai.nanofaas.examples.jsontransformlite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = (Map<String, Object>) handle.invoke(null, input);
            JsonNode expected = contractCase.get("expected");
            String name = contractCase.get("name").asText();
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
}
