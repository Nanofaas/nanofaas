package it.unimib.datai.nanofaas.runtime.core;

import it.unimib.datai.nanofaas.sdk.runtime.TraceLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLoggingFilterHeaderPriorityTest {

    @Test
    void executionId_fromHeader_takesPriorityOverEnv() throws ServletException, IOException {
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Execution-Id", "header-exec-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        FilterChain chain = (req, res) -> seen.set(MDC.get("executionId"));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isEqualTo("header-exec-123");
        assertThat(MDC.get("executionId")).isNull();
    }

    @Test
    void executionId_noHeader_fallsBackToEnv() throws ServletException, IOException {
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        FilterChain chain = (req, res) -> seen.set(MDC.get("executionId"));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isNull();
    }

    @Test
    void bothHeaders_setCorrectMdcEntries() throws ServletException, IOException {
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-abc");
        request.addHeader("X-Execution-Id", "exec-xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenExec = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            seenTrace.set(MDC.get("traceId"));
            seenExec.set(MDC.get("executionId"));
        };

        filter.doFilter(request, response, chain);

        assertThat(seenTrace.get()).isEqualTo("trace-abc");
        assertThat(seenExec.get()).isEqualTo("exec-xyz");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("executionId")).isNull();
    }

    @Test
    void mdcCleanedUp_evenOnException() throws ServletException, IOException {
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-err");
        request.addHeader("X-Execution-Id", "exec-err");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            throw new ServletException("boom");
        };

        try {
            filter.doFilter(request, response, chain);
        } catch (ServletException ignored) {
        }

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("executionId")).isNull();
    }
}
