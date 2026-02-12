package it.unimib.datai.nanofaas.common.model;

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
        assertNotNull(ExecutionMode.valueOf("POOL"));
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
    void scalingMetric_recordAccessors() {
        ScalingMetric m = new ScalingMetric("cpu", "80", null);
        assertEquals("cpu", m.type());
        assertEquals("80", m.target());
    }

    // --- ResourceSpec ---

    @Test
    void resourceSpec_recordAccessors() {
        ResourceSpec r = new ResourceSpec("250m", "512Mi");
        assertEquals("250m", r.cpu());
        assertEquals("512Mi", r.memory());
    }

    // --- ScalingConfig ---

    @Test
    void scalingConfig_recordAccessors() {
        ScalingConfig c = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10, null);
        assertEquals(ScalingStrategy.INTERNAL, c.strategy());
        assertEquals(1, c.minReplicas());
        assertEquals(10, c.maxReplicas());
    }
}
