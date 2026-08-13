package it.unimib.datai.nanofaas.examples.romannumeral;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RomanNumeralHandlerTest {

    private final RomanNumeralHandler handler = new RomanNumeralHandler();

    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "test-data", "roman-numeral", "correctness.json").toFile()).get("cases");
        for (JsonNode contractCase : cases) {
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Object result = handler.handle(new InvocationRequest(input, null));
            Map<String, Object> actual;
            if (result instanceof HandlerResponse response) {
                actual = (Map<String, Object>) response.output();
            } else {
                actual = (Map<String, Object>) result;
            }
            JsonNode expected = contractCase.get("expected");
            String key = expected.has("error") ? "error" : "roman";
            assertEquals(expected.get(key).asText(), actual.get(key), contractCase.get("name").asText());
        }
    }

    @Test
    void convertsKnownValues() {
        assertAll(
            () -> assertEquals("I",          RomanNumeralHandler.toRoman(1)),
            () -> assertEquals("IV",         RomanNumeralHandler.toRoman(4)),
            () -> assertEquals("IX",         RomanNumeralHandler.toRoman(9)),
            () -> assertEquals("XL",         RomanNumeralHandler.toRoman(40)),
            () -> assertEquals("XLII",       RomanNumeralHandler.toRoman(42)),
            () -> assertEquals("XC",         RomanNumeralHandler.toRoman(90)),
            () -> assertEquals("CD",         RomanNumeralHandler.toRoman(400)),
            () -> assertEquals("CM",         RomanNumeralHandler.toRoman(900)),
            () -> assertEquals("MCMXCIV",    RomanNumeralHandler.toRoman(1994)),
            () -> assertEquals("MMXXIV",     RomanNumeralHandler.toRoman(2024)),
            () -> assertEquals("MMMCMXCIX",  RomanNumeralHandler.toRoman(3999))
        );
    }

    @Test
    void handleReturnsMissingFieldError() {
        var req = new InvocationRequest(Map.of(), null);
        var result = (HandlerResponse) handler.handle(req);
        assertEquals(422, result.statusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.output();
        assertEquals("missing required field: number", body.get("error"));
    }

    @Test
    void handleReturnsRomanForValidNumber() {
        var req = new InvocationRequest(Map.of("number", 42), null);
        var result = handler.handle(req);
        assertFalse(result instanceof HandlerResponse); // unchanged: plain value, implicit 200
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result;
        assertEquals("XLII", body.get("roman"));
    }

    @Test
    void handleRejectsOutOfRangeNumber() {
        var req = new InvocationRequest(Map.of("number", 4000), null);
        var result = (HandlerResponse) handler.handle(req);
        assertEquals(422, result.statusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.output();
        assertTrue(((String) body.get("error")).startsWith("number must be between"));
    }
}
