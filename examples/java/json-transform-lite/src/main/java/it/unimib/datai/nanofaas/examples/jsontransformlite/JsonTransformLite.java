package it.unimib.datai.nanofaas.examples.jsontransformlite;

import it.unimib.datai.nanofaas.sdk.lite.FunctionContext;
import it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups an array of JSON objects by a field and applies an aggregate operation.
 *
 * Input:
 * <pre>{@code
 * {
 *   "data": [ {"dept": "eng", "salary": 80000}, ... ],
 *   "groupBy": "dept",
 *   "operation": "avg",   // count | sum | avg | min | max
 *   "valueField": "salary" // required for sum/avg/min/max
 * }
 * }</pre>
 */
public class JsonTransformLite {
    private static final Logger log = FunctionContext.getLogger(JsonTransformLite.class);

    public static void main(String[] args) {
        NanofaasRuntime.builder()
                .handler(request -> {
                    log.info("Processing json-transform for execution {}", FunctionContext.getExecutionId());
                    return handle(request.input());
                })
                .functionName("json-transform-lite")
                .build()
                .start();
    }

    @SuppressWarnings("unchecked")
    private static Object handle(Object rawInput) {
        Map<String, Object> input;
        try {
            input = (Map<String, Object>) rawInput;
        } catch (ClassCastException e) {
            return Map.of("error", "Input must be a JSON object");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) input.get("data");
        String groupBy = (String) input.get("groupBy");
        String operation = (String) input.getOrDefault("operation", "count");
        String valueField = (String) input.get("valueField");

        if (data == null || groupBy == null) {
            return Map.of("error", "Fields 'data' (array) and 'groupBy' (string) are required");
        }

        if (!operation.equals("count") && valueField == null) {
            return Map.of("error", "Field 'valueField' is required for operation: " + operation);
        }

        return transform(data, groupBy, operation, valueField);
    }

    private static Map<String, Object> transform(
            List<Map<String, Object>> data,
            String groupBy,
            String operation,
            String valueField) {

        Map<Object, List<Map<String, Object>>> grouped = data.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getOrDefault(groupBy, "null"),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, Object> groups = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            String key = String.valueOf(entry.getKey());
            List<Map<String, Object>> items = entry.getValue();

            Object value = switch (operation.toLowerCase()) {
                case "count" -> items.size();
                case "sum" -> sumField(items, valueField);
                case "avg" -> avgField(items, valueField);
                case "min" -> minField(items, valueField);
                case "max" -> maxField(items, valueField);
                default -> "unknown operation: " + operation;
            };

            groups.put(key, value);
        }

        return Map.of(
                "groupBy", groupBy,
                "operation", operation,
                "groups", groups
        );
    }

    private static double sumField(List<Map<String, Object>> items, String field) {
        return items.stream()
                .map(i -> i.get(field))
                .filter(Objects::nonNull)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .sum();
    }

    private static double avgField(List<Map<String, Object>> items, String field) {
        return items.stream()
                .map(i -> i.get(field))
                .filter(Objects::nonNull)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .average()
                .orElse(0.0);
    }

    private static double minField(List<Map<String, Object>> items, String field) {
        return items.stream()
                .map(i -> i.get(field))
                .filter(Objects::nonNull)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .min()
                .orElse(0.0);
    }

    private static double maxField(List<Map<String, Object>> items, String field) {
        return items.stream()
                .map(i -> i.get(field))
                .filter(Objects::nonNull)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .max()
                .orElse(0.0);
    }
}
