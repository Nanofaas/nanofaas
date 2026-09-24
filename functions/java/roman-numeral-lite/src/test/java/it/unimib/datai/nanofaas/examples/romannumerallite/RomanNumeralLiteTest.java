package it.unimib.datai.nanofaas.examples.romannumerallite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RomanNumeralLiteTest {

    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "test-data", "roman-numeral", "correctness.json").toFile()).get("cases");
        for (JsonNode contractCase : cases) {
            String name = contractCase.get("name").asText();
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = unwrap(RomanNumeralLite.handle(input), contractCase, name);
            JsonNode expected = contractCase.get("expected");
            String key = expected.has("error") ? "error" : "roman";
            assertEquals(expected.get(key).asText(), actual.get(key), name);
        }
    }

    @Test
    void convertsCanonicalValues() {
        assertEquals(Map.of("roman", "I"), RomanNumeralLite.handle(Map.of("number", 1)));
        assertEquals(Map.of("roman", "IV"), RomanNumeralLite.handle(Map.of("number", 4)));
        assertEquals(Map.of("roman", "IX"), RomanNumeralLite.handle(Map.of("number", 9)));
        assertEquals(Map.of("roman", "XLII"), RomanNumeralLite.handle(Map.of("number", 42)));
        assertEquals(Map.of("roman", "MCMXCIV"), RomanNumeralLite.handle(Map.of("number", 1994)));
        assertEquals(Map.of("roman", "MMMCMXCIX"), RomanNumeralLite.handle(Map.of("number", 3999)));
    }

    @Test
    void validatesInput() {
        assertEquals(
                HandlerResponse.of(Map.of("error", "missing required field: number"), 422),
                RomanNumeralLite.handle(Map.of()));
        assertEquals(
                HandlerResponse.of(Map.of("error", "field 'number' must be an integer"), 422),
                RomanNumeralLite.handle(Map.of("number", "42")));
        assertEquals(
                HandlerResponse.of(Map.of("error", "number must be between 1 and 3999, got: 0"), 422),
                RomanNumeralLite.handle(Map.of("number", 0)));
        assertEquals(
                HandlerResponse.of(Map.of("error", "Input must be a JSON object"), 422),
                RomanNumeralLite.handle(null));
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
