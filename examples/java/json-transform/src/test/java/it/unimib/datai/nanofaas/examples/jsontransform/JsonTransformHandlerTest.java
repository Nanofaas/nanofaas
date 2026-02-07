package it.unimib.datai.nanofaas.examples.jsontransform;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTransformHandlerTest {

    private final JsonTransformHandler handler = new JsonTransformHandler();

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
}
