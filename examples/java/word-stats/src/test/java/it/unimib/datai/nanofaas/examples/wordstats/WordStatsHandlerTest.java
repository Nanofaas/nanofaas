package it.unimib.datai.nanofaas.examples.wordstats;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WordStatsHandlerTest {

    private final WordStatsHandler handler = new WordStatsHandler();

    @Test
    @SuppressWarnings("unchecked")
    void basicTextAnalysis() {
        InvocationRequest req = new InvocationRequest(
                Map.of("text", "the quick brown fox jumps over the lazy dog"),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertEquals(9L, result.get("wordCount"));
        assertEquals(8L, result.get("uniqueWords")); // "the" appears twice
        assertNotNull(result.get("topWords"));
        assertNotNull(result.get("averageWordLength"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void topNLimitsResults() {
        InvocationRequest req = new InvocationRequest(
                Map.of("text", "a b c d e f g h", "topN", 3),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        List<Map<String, Object>> topWords = (List<Map<String, Object>>) result.get("topWords");

        assertEquals(3, topWords.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateWordsCounted() {
        InvocationRequest req = new InvocationRequest(
                Map.of("text", "hello hello hello world world", "topN", 2),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        List<Map<String, Object>> topWords = (List<Map<String, Object>>) result.get("topWords");

        assertEquals("hello", topWords.get(0).get("word"));
        assertEquals(3L, topWords.get(0).get("count"));
        assertEquals("world", topWords.get(1).get("word"));
        assertEquals(2L, topWords.get(1).get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stringInputTreatedAsText() {
        InvocationRequest req = new InvocationRequest("hello world", null);

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertEquals(2L, result.get("wordCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyTextReturnsError() {
        InvocationRequest req = new InvocationRequest(Map.of("text", ""), null);

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertTrue(result.containsKey("error"));
    }
}
