package it.unimib.datai.nanofaas.examples.jsontransform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTransformHandlerTest {

    private final JsonTransformHandler handler = new JsonTransformHandler();

    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "contract-tests", "json-transform.json").toFile()).get("cases");
        for (JsonNode contractCase : cases) {
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Map<String, Object> actual = (Map<String, Object>) handler.handle(new InvocationRequest(input, null));
            JsonNode expected = contractCase.get("expected");
            String name = contractCase.get("name").asText();
            if (expected.has("error")) {
                assertEquals(expected.get("error").asText(), actual.get("error"), name);
            } else {
                assertEquals(expected.get("groupBy").asText(), actual.get("groupBy"), name);
                assertEquals(expected.get("operation").asText(), actual.get("operation"), name);
                Map<String, Object> groups = (Map<String, Object>) actual.get("groups");
                expected.get("groups").fields().forEachRemaining(entry ->
                        assertEquals(entry.getValue().asDouble(), ((Number) groups.get(entry.getKey())).doubleValue(), name));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void countByGroup() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "sales", "salary", 60000),
                                Map.of("dept", "eng", "salary", 90000)
                        ),
                        "groupBy", "dept",
                        "operation", "count"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertEquals(2, groups.get("eng"));
        assertEquals(1, groups.get("sales"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sumByGroup() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "eng", "salary", 90000),
                                Map.of("dept", "sales", "salary", 60000)
                        ),
                        "groupBy", "dept",
                        "operation", "sum",
                        "valueField", "salary"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertEquals(170000.0, groups.get("eng"));
        assertEquals(60000.0, groups.get("sales"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void avgByGroup() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "eng", "salary", 90000)
                        ),
                        "groupBy", "dept",
                        "operation", "avg",
                        "valueField", "salary"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertEquals(85000.0, groups.get("eng"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingGroupByReturnsError() {
        InvocationRequest req = new InvocationRequest(
                Map.of("data", List.of()),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertTrue(result.containsKey("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingValueFieldForSumReturnsError() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(Map.of("dept", "eng")),
                        "groupBy", "dept",
                        "operation", "sum"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertTrue(result.containsKey("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void minByGroup() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "eng", "salary", 90000)
                        ),
                        "groupBy", "dept",
                        "operation", "min",
                        "valueField", "salary"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertEquals(80000.0, groups.get("eng"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void maxByGroup() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "eng", "salary", 90000)
                        ),
                        "groupBy", "dept",
                        "operation", "max",
                        "valueField", "salary"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertEquals(90000.0, groups.get("eng"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownOperation_returnsUnknownMessage() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(Map.of("dept", "eng", "salary", 100)),
                        "groupBy", "dept",
                        "operation", "median",
                        "valueField", "salary"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertTrue(groups.get("eng").toString().contains("unknown operation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonMapInput_returnsError() {
        InvocationRequest req = new InvocationRequest("not a map", null);

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);

        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("JSON object"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultOperationIsCount() {
        InvocationRequest req = new InvocationRequest(
                Map.of(
                        "data", List.of(
                                Map.of("dept", "eng"),
                                Map.of("dept", "eng"),
                                Map.of("dept", "sales")
                        ),
                        "groupBy", "dept"
                ),
                null
        );

        Map<String, Object> result = (Map<String, Object>) handler.handle(req);
        Map<String, Object> groups = (Map<String, Object>) result.get("groups");

        assertEquals(2, groups.get("eng"));
        assertEquals(1, groups.get("sales"));
    }
}
