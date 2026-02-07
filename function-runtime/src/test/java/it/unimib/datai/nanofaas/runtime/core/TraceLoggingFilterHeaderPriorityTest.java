package it.unimib.datai.nanofaas.runtime.core;

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
        // The env var EXECUTION_ID is cached at construction time.
        // When the header X-Execution-Id is provided, it should override the env value.
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Execution-Id", "header-exec-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        FilterChain chain = (req, res) -> seen.set(MDC.get("executionId"));

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isEqualTo("header-exec-123");
        // MDC is cleaned up after filter
        assertThat(MDC.get("executionId")).isNull();
    }

    @Test
    void executionId_noHeader_fallsBackToEnv() throws ServletException, IOException {
        // Without the header, the filter should use the env var (which is null in test).
        TraceLoggingFilter filter = new TraceLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        FilterChain chain = (req, res) -> seen.set(MDC.get("executionId"));

        filter.doFilter(request, response, chain);

        // In test env, EXECUTION_ID env var is null, so executionId should be null
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
        // Both cleaned up
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
