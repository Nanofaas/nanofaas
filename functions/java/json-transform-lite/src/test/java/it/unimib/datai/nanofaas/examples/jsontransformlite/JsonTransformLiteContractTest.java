package it.unimib.datai.nanofaas.examples.jsontransformlite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTransformLiteContractTest {
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
