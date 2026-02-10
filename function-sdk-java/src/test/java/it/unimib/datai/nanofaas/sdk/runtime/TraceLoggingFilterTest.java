package it.unimib.datai.nanofaas.sdk.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TraceLoggingFilterTest {

    private final TraceLoggingFilter filter = new TraceLoggingFilter();

    @Test
    void bothHeaders_populatesMDC() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-123");
        request.addHeader("X-Execution-Id", "exec-456");

        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedExec = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedTrace.set(MDC.get("traceId"));
            capturedExec.set(MDC.get("executionId"));
        };

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertEquals("trace-123", capturedTrace.get());
        assertEquals("exec-456", capturedExec.get());

        // MDC should be cleaned after filter
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("executionId"));
    }

    @Test
    void noHeaders_mdcRemainsEmpty() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();

        AtomicReference<String> capturedTrace = new AtomicReference<>();
        AtomicReference<String> capturedExec = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedTrace.set(MDC.get("traceId"));
            capturedExec.set(MDC.get("executionId"));
        };

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertNull(capturedTrace.get());
        // executionId may be set from env var - just verify it doesn't throw
    }

    @Test
    void onlyTraceHeader_setsOnlyTraceId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-only");

        AtomicReference<String> capturedTrace = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedTrace.set(MDC.get("traceId"));
        };

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertEquals("trace-only", capturedTrace.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void filterChainException_mdcStillCleaned() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-err");
        request.addHeader("X-Execution-Id", "exec-err");

        FilterChain chain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class,
                () -> filter.doFilterInternal(request, new MockHttpServletResponse(), chain));

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("executionId"));
    }
}
