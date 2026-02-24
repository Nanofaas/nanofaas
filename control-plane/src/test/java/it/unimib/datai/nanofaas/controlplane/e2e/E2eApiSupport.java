package it.unimib.datai.nanofaas.controlplane.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

final class E2eApiSupport {

    private static final Pattern METRIC_LINE_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z_:][A-Za-z0-9_:]*)(\\{([^}]*)})?\\s+([-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*$");
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CONCURRENCY = 2;
    private static final int DEFAULT_QUEUE_SIZE = 20;
    private static final int DEFAULT_RETRIES = 3;

    private E2eApiSupport() {
    }

    static Map<String, Object> poolFunctionSpec(
            String name,
            String image,
            String endpointUrl,
            int timeoutMs,
            int concurrency,
            int queueSize,
            int maxRetries) {
        return Map.of(
                "name", name,
                "image", image,
                "timeoutMs", timeoutMs,
                "concurrency", concurrency,
                "queueSize", queueSize,
                "maxRetries", maxRetries,
                "executionMode", "POOL",
                "endpointUrl", endpointUrl
        );
    }

    static Map<String, Object> poolFunctionSpec(String name, String image, String endpointUrl) {
        return poolFunctionSpec(name, image, endpointUrl,
                DEFAULT_TIMEOUT_MS, DEFAULT_CONCURRENCY, DEFAULT_QUEUE_SIZE, DEFAULT_RETRIES);
    }

    static void registerFunction(Map<String, Object> spec) {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(spec)
                .post("/v1/functions")
                .then()
                .statusCode(201)
                .body("name", equalTo(spec.get("name")));
    }

    static void registerPoolFunction(String name, String image, String endpointUrl) {
        registerFunction(poolFunctionSpec(name, image, endpointUrl));
    }

    static void awaitSyncInvokeSuccess(String functionName, String message) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> RestAssured.given()
                        .contentType(ContentType.JSON)
                        .body(Map.of("input", Map.of("message", message)))
                        .post("/v1/functions/" + functionName + ":invoke")
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("success"))
                        .body("output.message", equalTo(message)));
    }

    static Response invokeSync(String functionName, Object input) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", input))
                .post("/v1/functions/" + functionName + ":invoke");
    }

    static List<Response> invokeSyncBurst(String functionName, int count, IntFunction<Object> inputFactory) {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<CompletableFuture<Response>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> invokeSync(functionName, inputFactory.apply(index)), executor));
            }
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    static String enqueue(String functionName, Object input, String idempotencyKey) {
        var request = RestAssured.given()
                .contentType(ContentType.JSON);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request
                .body(Map.of("input", input))
                .post("/v1/functions/" + functionName + ":enqueue")
                .then()
                .statusCode(202)
                .body("executionId", notNullValue())
                .extract()
                .path("executionId");
    }

    static String enqueue(String functionName, Object input) {
        return enqueue(functionName, input, null);
    }

    static void awaitExecutionSuccess(String executionId, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> RestAssured.get("/v1/executions/{id}", executionId)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("success")));
    }

    static String fetchPrometheusMetrics(String url) {
        return RestAssured.get(url)
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    static void assertMetricPresent(String metrics, String metric) {
        assertMetricPresentAny(metrics, metric);
    }

    static void assertMetricPresentAny(String metrics, String... metricCandidates) {
        for (String metric : metricCandidates) {
            MetricAggregate aggregate = aggregateMetric(metrics, metric, Map.of());
            if (aggregate.matches() > 0) {
                return;
            }
        }
        throw new AssertionError("expected one of metrics " + List.of(metricCandidates) + " to be present");
    }

    static double metricSum(String metrics, String metric, Map<String, String> labelFilter) {
        return aggregateMetric(metrics, metric, labelFilter).sum();
    }

    static void assertMetricSumAtLeast(String metrics, String metric, Map<String, String> labelFilter, double min) {
        assertMetricSumAtLeastAny(metrics, labelFilter, min, metric);
    }

    static void assertMetricSumAtLeastAny(
            String metrics,
            Map<String, String> labelFilter,
            double min,
            String... metricCandidates) {
        MetricAggregate firstMatched = null;
        String matchedMetric = null;
        for (String metric : metricCandidates) {
            MetricAggregate aggregate = aggregateMetric(metrics, metric, labelFilter);
            if (aggregate.matches() > 0) {
                firstMatched = aggregate;
                matchedMetric = metric;
                break;
            }
        }
        if (firstMatched == null || matchedMetric == null) {
            throw new AssertionError(
                    "expected one of metrics " + List.of(metricCandidates) + " with labels " + labelFilter + " to be present");
        }
        if (firstMatched.sum() < min) {
            throw new AssertionError("expected " + matchedMetric + " sum >= " + min + " but was " + firstMatched.sum());
        }
    }

    private static MetricAggregate aggregateMetric(String metrics, String metric, Map<String, String> labelFilter) {
        double sum = 0;
        int matches = 0;
        for (String line : metrics.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = METRIC_LINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String metricName = matcher.group(1);
            if (!Objects.equals(metric, metricName)) {
                continue;
            }
            Map<String, String> labels = parseLabels(matcher.group(3));
            if (!labelsMatch(labels, labelFilter)) {
                continue;
            }
            sum += Double.parseDouble(matcher.group(4));
            matches++;
        }
        return new MetricAggregate(sum, matches);
    }

    private static Map<String, String> parseLabels(String rawLabels) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (rawLabels == null || rawLabels.isBlank()) {
            return labels;
        }
        for (String token : rawLabels.split(",")) {
            String pair = token.trim();
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim();
            String value = kv[1].trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
            }
            labels.put(key, value);
        }
        return labels;
    }

    private static boolean labelsMatch(Map<String, String> labels, Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!Objects.equals(labels.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private record MetricAggregate(double sum, int matches) {
    }
}
