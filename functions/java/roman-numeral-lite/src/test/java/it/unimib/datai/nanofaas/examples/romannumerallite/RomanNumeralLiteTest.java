package it.unimib.datai.nanofaas.examples.romannumerallite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RomanNumeralLiteTest {

    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "contract-tests", "roman-numeral.json").toFile()).get("cases");
        for (JsonNode contractCase : cases) {
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = (Map<String, Object>) RomanNumeralLite.handle(input);
            JsonNode expected = contractCase.get("expected");
            String key = expected.has("error") ? "error" : "roman";
            assertEquals(expected.get(key).asText(), actual.get(key), contractCase.get("name").asText());
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
                Map.of("error", "missing required field: number"),
                RomanNumeralLite.handle(Map.of()));
        assertEquals(
                Map.of("error", "field 'number' must be an integer"),
                RomanNumeralLite.handle(Map.of("number", "42")));
        assertEquals(
                Map.of("error", "number must be between 1 and 3999, got: 0"),
                RomanNumeralLite.handle(Map.of("number", 0)));
        assertEquals(
                Map.of("error", "Input must be a JSON object"),
                RomanNumeralLite.handle(null));
    }
}
