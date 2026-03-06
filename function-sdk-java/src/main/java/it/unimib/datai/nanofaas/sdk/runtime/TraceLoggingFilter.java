package it.unimib.datai.nanofaas.sdk.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceLoggingFilter extends OncePerRequestFilter {
    private final String envExecutionId;

    public TraceLoggingFilter() {
        this(System.getenv("EXECUTION_ID"));
    }

    TraceLoggingFilter(String envExecutionId) {
        this.envExecutionId = envExecutionId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        // Prefer header (warm mode) over env (one-shot mode)
        String executionId = request.getHeader("X-Execution-Id");
        if (executionId == null || executionId.isBlank()) {
            executionId = envExecutionId;
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
