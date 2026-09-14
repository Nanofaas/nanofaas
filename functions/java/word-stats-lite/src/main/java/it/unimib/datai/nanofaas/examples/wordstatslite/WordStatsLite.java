package it.unimib.datai.nanofaas.examples.wordstatslite;

import it.unimib.datai.nanofaas.sdk.lite.FunctionContext;
import it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class WordStatsLite {
    private static final Logger log = FunctionContext.getLogger(WordStatsLite.class);

    @SuppressWarnings("java:S1172") // args is required by the JVM main(String[]) contract
    public static void main(String[] args) {
        NanofaasRuntime.builder()
                .handler(request -> handle(request.input()))
                .functionName("word-stats-lite")
                .build()
                .start();
    }

    static Object handle(Object rawInput) {
        log.info("Processing word stats for execution {}", FunctionContext.getExecutionId());

        Map<String, Object> input = toMap(rawInput);
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
        String[] words = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", "")
                .split("\\s+");

        if (words.length == 1 && words[0].isEmpty()) {
            return Map.of("error", "No words found in input");
        }

        Map<String, Long> freq = Arrays.stream(words)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));

        List<Map<String, Object>> topWords = freq.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Long.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
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
