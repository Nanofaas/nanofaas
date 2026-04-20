package it.unimib.datai.nanofaas.examples.romannumeral;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RomanNumeralHandlerTest {

    private final RomanNumeralHandler handler = new RomanNumeralHandler();

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
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) handler.handle(req);
        assertEquals("missing required field: number", result.get("error"));
    }

    @Test
    void handleReturnsRomanForValidNumber() {
        var req = new InvocationRequest(Map.of("number", 42), null);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) handler.handle(req);
        assertEquals("XLII", result.get("roman"));
    }

    @Test
    void handleRejectsOutOfRangeNumber() {
        var req = new InvocationRequest(Map.of("number", 4000), null);
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) handler.handle(req);
        assertTrue(((String) result.get("error")).startsWith("number must be between"));
    }
}
