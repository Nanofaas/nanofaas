package it.unimib.datai.nanofaas.examples.wordstats;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;

import java.util.*;

/** The shared word-stats-runtime-v1 contract; see docs/word-stats-contract.md. */
@NanofaasFunction
public class WordStatsHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        Map<?, ?> input = request.input() instanceof Map<?, ?> map ? map : Map.of();
        Object rawText = request.input() instanceof String ? request.input() : input.get("text");
        if (!(rawText instanceof String text) || text.isEmpty()) {
            return Map.of("error", "Field 'text' is required and must be non-empty");
        }
        double topN = input.get("topN") instanceof Number value ? value.doubleValue() : 10;
        if (!Double.isFinite(topN)) topN = 10;
        return analyze(text, topN);
    }

    private Map<String, Object> analyze(String text, double topN) {
        Map<String, Long> frequencies = new HashMap<>();
        StringBuilder word = new StringBuilder();
        long count = 0, totalLength = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            int category = Character.getType(cp);
            if (Character.isLetter(cp) || category == Character.DECIMAL_DIGIT_NUMBER
                    || category == Character.LETTER_NUMBER || category == Character.OTHER_NUMBER) {
                word.appendCodePoint(Character.toLowerCase(cp));
                totalLength++;
            } else if (!word.isEmpty()) {
                frequencies.merge(word.toString(), 1L, Long::sum);
                count++;
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) {
            frequencies.merge(word.toString(), 1L, Long::sum);
            count++;
        }
        if (count == 0) return Map.of("error", "No words found in input");
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(frequencies.entrySet());
        ordered.sort((a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : compareScalars(a.getKey(), b.getKey());
        });
        int limit = (int) Math.min(ordered.size(), Math.max(0, Math.floor(topN)));
        List<Map<String, Object>> topWords = ordered.subList(0, limit).stream()
                .map(e -> Map.<String, Object>of("word", e.getKey(), "count", e.getValue())).toList();
        long cents = (200 * totalLength + count) / (2 * count);
        return Map.of("wordCount", count, "uniqueWords", (long) frequencies.size(),
                "topWords", topWords, "averageWordLength", cents / 100.0);
    }

    private static int compareScalars(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int left = a.codePointAt(i), right = b.codePointAt(j);
            if (left != right) return Integer.compare(left, right);
            i += Character.charCount(left);
            j += Character.charCount(right);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
