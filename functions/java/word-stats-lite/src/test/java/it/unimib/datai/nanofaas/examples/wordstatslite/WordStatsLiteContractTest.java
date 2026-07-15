package it.unimib.datai.nanofaas.examples.wordstatslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordStatsLiteContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "test-data", "word-stats", "correctness.json").toFile()).get("cases");
        Method handle = WordStatsLite.class.getDeclaredMethod("handle", Object.class);
        handle.setAccessible(true);
        for (JsonNode contractCase : cases) {
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = (Map<String, Object>) handle.invoke(null, input);
            JsonNode expected = contractCase.get("expected");
            String name = contractCase.get("name").asText();
            if (expected.has("error")) {
                assertEquals(expected.get("error").asText(), actual.get("error"), name);
                continue;
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
    }
}
