package it.unimib.datai.nanofaas.common.model;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommonModelTest {

    // --- InvocationResult ---

    @Test
    void invocationResult_success_hasOutput() {
        InvocationResult r = InvocationResult.success("hello");
        assertTrue(r.success());
        assertEquals("hello", r.output());
        assertNull(r.error());
    }

    @Test
    void invocationResult_error_hasErrorInfo() {
        InvocationResult r = InvocationResult.error("TIMEOUT", "timed out");
        assertFalse(r.success());
        assertNull(r.output());
        assertNotNull(r.error());
        assertEquals("TIMEOUT", r.error().code());
        assertEquals("timed out", r.error().message());
    }

    @Test
    void invocationResult_success_hasNullEnvelopeFieldsByDefault() {
        InvocationResult r = InvocationResult.success("hello");
        assertNull(r.statusCode());
        assertNull(r.headers());
        assertNull(r.encoding());
    }

    @Test
    void invocationResult_successWithEnvelope_carriesFields() {
        InvocationResult r = InvocationResult.successWithEnvelope("body", 201, Map.of("Location", "/x"), "base64");
        assertTrue(r.success());
        assertEquals("body", r.output());
        assertEquals(201, r.statusCode());
        assertEquals("/x", r.headers().get("Location"));
        assertEquals("base64", r.encoding());
    }

    @Test
    void invocationResult_jsonRoundTrip() {
        // This record is the @RequestBody of /internal/executions/{id}:complete — the
        // async callback wire format. Field names must survive both directions.
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationResult original = InvocationResult.successWithEnvelope(
                "body", 201, Map.of("Location", "/x"), "base64");
        InvocationResult back = mapper.readValue(mapper.writeValueAsString(original), InvocationResult.class);
        assertEquals(201, back.statusCode());
        assertEquals("/x", back.headers().get("Location"));
        assertEquals("base64", back.encoding());
    }

    @Test
    void invocationResult_deserializesLegacyCallbackBody() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationResult back = mapper.readValue(
                "{\"success\":true,\"output\":\"ok\",\"error\":null}", InvocationResult.class);
        assertTrue(back.success());
        assertNull(back.statusCode());
    }

    // --- ErrorInfo ---

    @Test
    void errorInfo_recordAccessors() {
        ErrorInfo e = new ErrorInfo("CODE", "msg");
        assertEquals("CODE", e.code());
        assertEquals("msg", e.message());
    }

    // --- InvocationRequest ---

    @Test
    void invocationRequest_recordAccessors() {
        InvocationRequest r = new InvocationRequest("payload", Map.of("k", "v"));
        assertEquals("payload", r.input());
        assertEquals("v", r.metadata().get("k"));
    }

    @Test
    void invocationRequest_nullMetadata() {
        InvocationRequest r = new InvocationRequest("data", null);
        assertNull(r.metadata());
    }

    @Test
    void invocationRequest_headersAccessor() {
        InvocationRequest r = new InvocationRequest("payload", Map.of("k", "v"), Map.of("authorization", "Bearer x"));
        assertEquals("Bearer x", r.headers().get("authorization"));
    }

    @Test
    void invocationRequest_nullHeaders() {
        InvocationRequest r = new InvocationRequest("data", null, null);
        assertNull(r.headers());
    }

    @Test
    void invocationRequest_jsonRoundTrip() throws Exception {
        // The record is @RequestBody-deserialized on two hops; adding a secondary
        // constructor must not confuse Jackson's canonical-constructor detection.
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationRequest original = new InvocationRequest(
                Map.of("number", 42), Map.of("k", "v"), Map.of("authorization", "Bearer x"));
        InvocationRequest back = mapper.readValue(mapper.writeValueAsString(original), InvocationRequest.class);
        assertEquals(original.metadata(), back.metadata());
        assertEquals(original.headers(), back.headers());
    }

    @Test
    void invocationRequest_deserializesLegacyBodyWithoutHeaders() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationRequest back = mapper.readValue("{\"input\":{\"n\":1}}", InvocationRequest.class);
        assertNull(back.headers());
    }

    // --- InvocationResponse ---

    @Test
    void invocationResponse_recordAccessors() {
        ErrorInfo err = new ErrorInfo("ERR", "detail");
        InvocationResponse resp = new InvocationResponse("ex-1", "FAILED", null, err);
        assertEquals("ex-1", resp.executionId());
        assertEquals("FAILED", resp.status());
        assertNull(resp.output());
        assertEquals(err, resp.error());
    }

    // --- ExecutionStatus ---

    @Test
    void executionStatus_recordAccessors() {
        ExecutionStatus s = new ExecutionStatus("ex-1", "COMPLETED", Instant.ofEpochMilli(100), Instant.ofEpochMilli(200), "result", null, true, 150L);
        assertEquals("ex-1", s.executionId());
        assertEquals("COMPLETED", s.status());
        assertEquals(Instant.ofEpochMilli(100), s.startedAt());
        assertEquals(Instant.ofEpochMilli(200), s.finishedAt());
        assertEquals("result", s.output());
        assertNull(s.error());
        assertTrue(s.coldStart());
        assertEquals(150L, s.initDurationMs());
    }

    // --- FunctionSpec ---

    @Test
    void functionSpec_recordAccessors() {
        FunctionSpec spec = new FunctionSpec(
                "echo", "img:latest", List.of("cmd"), null, null,
                30000, 4, 100, 3, "http://svc", ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP, null, null);
        assertEquals("echo", spec.name());
        assertEquals("img:latest", spec.image());
        assertEquals(ExecutionMode.DEPLOYMENT, spec.executionMode());
        assertEquals(RuntimeMode.HTTP, spec.runtimeMode());
    }

    // --- Enums ---

    @Test
    void executionMode_values() {
        assertEquals(3, ExecutionMode.values().length);
        assertNotNull(ExecutionMode.valueOf("LOCAL"));
        assertNotNull(ExecutionMode.valueOf("EXTERNAL"));
        assertNotNull(ExecutionMode.valueOf("DEPLOYMENT"));
    }

    @Test
    void runtimeMode_values() {
        assertEquals(3, RuntimeMode.values().length);
        assertNotNull(RuntimeMode.valueOf("HTTP"));
        assertNotNull(RuntimeMode.valueOf("STDIO"));
        assertNotNull(RuntimeMode.valueOf("FILE"));
    }

    @Test
    void scalingStrategy_values() {
        assertEquals(3, ScalingStrategy.values().length);
        assertNotNull(ScalingStrategy.valueOf("HPA"));
        assertNotNull(ScalingStrategy.valueOf("INTERNAL"));
        assertNotNull(ScalingStrategy.valueOf("NONE"));
    }

    @Test
    void concurrencyControlMode_values() {
        assertEquals(3, ConcurrencyControlMode.values().length);
        assertNotNull(ConcurrencyControlMode.valueOf("FIXED"));
        assertNotNull(ConcurrencyControlMode.valueOf("STATIC_PER_POD"));
        assertNotNull(ConcurrencyControlMode.valueOf("ADAPTIVE_PER_POD"));
    }

    @Test
    void concurrencyControlConfig_recordAccessors() {
        ConcurrencyControlConfig c = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD,
                2,
                1,
                6,
                30000L,
                60000L,
                0.8,
                0.2
        );
        assertEquals(ConcurrencyControlMode.STATIC_PER_POD, c.mode());
        assertEquals(2, c.targetInFlightPerPod());
        assertEquals(1, c.minTargetInFlightPerPod());
        assertEquals(6, c.maxTargetInFlightPerPod());
        assertEquals(30000L, c.upscaleCooldownMs());
        assertEquals(60000L, c.downscaleCooldownMs());
        assertEquals(0.8, c.highLoadThreshold());
        assertEquals(0.2, c.lowLoadThreshold());
    }

    @Test
    void scalingMetric_recordAccessors() {
        ScalingMetric m = new ScalingMetric("cpu", "80", null);
        assertEquals("cpu", m.type());
        assertEquals("80", m.target());
    }

    // --- ResourceSpec ---

    @Test
    void resourceSpec_recordAccessors() {
        ResourceQuantity requests = new ResourceQuantity(new BigDecimal("0.25"), 256);
        ResourceQuantity limits = new ResourceQuantity(BigDecimal.ONE, 512);
        ResourceSpec r = new ResourceSpec(requests, limits);

        assertEquals(new BigDecimal("0.25"), r.requests().cpu());
        assertEquals(256, r.requests().memoryMiB());
        assertEquals(BigDecimal.ONE, r.limits().cpu());
        assertEquals(512, r.limits().memoryMiB());
    }

    // --- ScalingConfig ---

    @Test
    void scalingConfig_recordAccessors() {
        ConcurrencyControlConfig control = new ConcurrencyControlConfig(
                ConcurrencyControlMode.FIXED,
                2,
                1,
                4,
                1000L,
                2000L,
                0.7,
                0.3
        );
        ScalingConfig c = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10, null, control);
        assertEquals(ScalingStrategy.INTERNAL, c.strategy());
        assertEquals(1, c.minReplicas());
        assertEquals(10, c.maxReplicas());
        assertEquals(control, c.concurrencyControl());
    }
}
