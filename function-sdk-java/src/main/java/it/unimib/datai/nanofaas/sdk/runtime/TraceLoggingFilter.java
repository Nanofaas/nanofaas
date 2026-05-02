package it.unimib.datai.nanofaas.sdk.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Copies execution metadata into SLF4J MDC for the duration of the request.
 *
 * <p>This is what keeps logs from the runtime and downstream handler code correlated with the
 * same trace and execution identifiers. The filter runs on each request and clears the MDC when
 * the request completes so the values do not leak across invocations.</p>
 */
@Component
public class TraceLoggingFilter extends OncePerRequestFilter {
    private final RuntimeSettings runtimeSettings;

    public TraceLoggingFilter(RuntimeSettings runtimeSettings) {
        this.runtimeSettings = runtimeSettings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        // Prefer header (warm mode) over env (one-shot mode)
        String executionId = request.getHeader("X-Execution-Id");
        if (executionId == null || executionId.isBlank()) {
            executionId = runtimeSettings.executionId();
        }
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        if (executionId != null && !executionId.isBlank()) {
            MDC.put("executionId", executionId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("executionId");
        }
    }
}
