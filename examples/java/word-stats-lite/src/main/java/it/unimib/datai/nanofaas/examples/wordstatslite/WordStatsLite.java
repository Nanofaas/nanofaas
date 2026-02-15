package it.unimib.datai.nanofaas.examples.wordstatslite;

import it.unimib.datai.nanofaas.sdk.lite.FunctionContext;
import it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WordStatsLite {
    private static final Logger log = FunctionContext.getLogger(WordStatsLite.class);

    public static void main(String[] args) {
        NanofaasRuntime.builder()
                .handler(request -> {
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
                })
                .functionName("word-stats-lite")
                .build()
                .start();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object input) {
        if (input instanceof Map) {
            return (Map<String, Object>) input;
        }
        if (input instanceof String s) {
            return Map.of("text", s);
        }
        return Map.of();
    }

    private static Map<String, Object> analyze(String text, int topN) {
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
