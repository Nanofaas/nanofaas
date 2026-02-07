package it.unimib.datai.nanofaas.examples.wordstats;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.FunctionContext;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes text and returns word statistics: total count, unique count,
 * top-N frequencies, and average word length.
 *
 * Input: {@code {"text": "...", "topN": 5}}  (topN defaults to 10)
 */
@NanofaasFunction
public class WordStatsHandler implements FunctionHandler {
    private static final Logger log = FunctionContext.getLogger(WordStatsHandler.class);

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(InvocationRequest request) {
        log.info("Processing word stats for execution {}", FunctionContext.getExecutionId());

        Map<String, Object> input = toMap(request.input());
        String text = (String) input.get("text");
        if (text == null || text.isBlank()) {
            return Map.of("error", "Field 'text' is required and must be non-empty");
        }

        int topN = input.containsKey("topN")
                ? ((Number) input.get("topN")).intValue()
                : 10;

        return analyze(text, topN);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object input) {
        if (input instanceof Map) {
            return (Map<String, Object>) input;
        }
        if (input instanceof String s) {
            return Map.of("text", s);
        }
        return Map.of();
    }

    private Map<String, Object> analyze(String text, int topN) {
        String[] words = text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s]", "")
                .split("\\s+");

        if (words.length == 1 && words[0].isEmpty()) {
            return Map.of("error", "No words found in input");
        }

        Map<String, Long> freq = Arrays.stream(words)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));

        List<Map<String, Object>> topWords = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(e -> Map.<String, Object>of("word", e.getKey(), "count", e.getValue()))
                .toList();

        double avgLen = Arrays.stream(words)
                .mapToInt(String::length)
                .average()
                .orElse(0.0);

        return Map.of(
                "wordCount", (long) words.length,
                "uniqueWords", (long) freq.size(),
                "topWords", topWords,
                "averageWordLength", Math.round(avgLen * 100.0) / 100.0
        );
    }
}
