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
 * Copies request metadata into MDC before user code runs.
 *
 * <p>This filter exists because handler code and supporting libraries need correlation fields for
 * logs, but the values originate in HTTP headers or startup env vars. The filter is the earliest
 * place where the runtime can normalize those values for the current request thread.</p>
 *
 * <p>Environment assumptions: the incoming request may provide {@code X-Execution-Id} and
 * {@code X-Trace-Id}. If execution id is missing, the runtime falls back to startup config. The
 * MDC entries are cleared after the request finishes so they do not leak across invocations.</p>
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
