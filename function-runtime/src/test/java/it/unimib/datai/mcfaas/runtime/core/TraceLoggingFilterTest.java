package it.unimib.datai.mcfaas.runtime.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceLoggingFilterTest {
    @Test
    void issue016_traceIdStoredInMdc() throws ServletException, IOException {
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        FilterChain chain = (req, res) -> seen.set(MDC.get("traceId"));

        filter.doFilter(request, response, chain);

        assertEquals("trace-123", seen.get());
        assertNull(MDC.get("traceId"));
    }
}
