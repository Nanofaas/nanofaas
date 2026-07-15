package it.unimib.datai.nanofaas.examples.wordstats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WordStatsHandlerTest {

    private final WordStatsHandler handler = new WordStatsHandler();

    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "test-data", "word-stats", "correctness.json").toFile()).get("cases");
        for (JsonNode contractCase : cases) {
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = (Map<String, Object>) handler.handle(new InvocationRequest(input, null));
            assertWordStats(contractCase.get("expected"), actual, contractCase.get("name").asText());
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertWordStats(JsonNode expected, Map<String, Object> actual, String name) {
        if (expected.has("error")) {
            assertEquals(expected.get("error").asText(), actual.get("error"), name);
            return;
        }
        assertEquals(expected.get("wordCount").asInt(), ((Number) actual.get("wordCount")).intValue(), name);
        assertEquals(expected.get("uniqueWords").asInt(), ((Number) actual.get("uniqueWords")).intValue(), name);
        assertEquals(expected.get("averageWordLength").asDouble(), ((Number) actual.get("averageWordLength")).doubleValue(), name);
        List<Map<String, Object>> topWords = (List<Map<String, Object>>) actual.get("topWords");
        for (int i = 0; i < expected.get("topWords").size(); i++) {
            assertEquals(expected.get("topWords").get(i).get("word").asText(), topWords.get(i).get("word"), name);
            assertEquals(expected.get("topWords").get(i).get("count").asInt(), ((Number) topWords.get(i).get("count")).intValue(), name);
        }
    }

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
