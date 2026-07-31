package it.unimib.datai.nanofaas.examples.figlet;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FigletHandlerTest {

    private final FigletHandler handler = new FigletHandler();

    @Test
    void rendersAsciiArt() {
        var req = new InvocationRequest(Map.of("text", "Hi"), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertEquals("Hi", result.get("text"));
        assertEquals("standard", result.get("font"));
        String art = (String) result.get("asciiArt");
        assertNotNull(art);
        assertFalse(art.isBlank());
    }

    @Test
    void acceptsStringInput() {
        var req = new InvocationRequest("nanoFaaS", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertEquals("nanoFaaS", result.get("text"));
        assertNotNull(result.get("asciiArt"));
    }

    @Test
    void rejectsBlankText() {
        var req = new InvocationRequest(Map.of("text", ""), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        assertTrue(result.containsKey("error"));
    }

    @Test
    void supportsCustomFont() {
        var req = new InvocationRequest(Map.of("text", "OK", "font", "slant"), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertEquals("slant", result.get("font"));
        assertNotNull(result.get("asciiArt"));
    }

    @Test
    void fallsBackOnUnknownFont() {
        var req = new InvocationRequest(Map.of("text", "x", "font", "nonexistent"), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertEquals("standard", result.get("font"));
        assertNotNull(result.get("asciiArt"));
    }

    @Test
    void outputMatchesSystemFiglet() {
        var req = new InvocationRequest(Map.of("text", "nanoFaaS"), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        String art = (String) result.get("asciiArt");
        assertNotNull(art);
        assertFalse(art.isBlank(), "ASCII art output must not be blank");
        assertTrue(art.lines().count() >= 5, "FIGlet output must span multiple lines");
    }
}
