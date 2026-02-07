package it.unimib.datai.nanofaas.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class FunctionContextTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void getExecutionId_returnsMdcValue() {
        MDC.put("executionId", "exec-123");
        assertEquals("exec-123", FunctionContext.getExecutionId());
    }

    @Test
    void getExecutionId_returnsNullWhenNotSet() {
        assertNull(FunctionContext.getExecutionId());
    }

    @Test
    void getTraceId_returnsMdcValue() {
        MDC.put("traceId", "trace-456");
        assertEquals("trace-456", FunctionContext.getTraceId());
    }

    @Test
    void getLogger_returnsNonNull() {
        assertNotNull(FunctionContext.getLogger(FunctionContextTest.class));
    }
}
