package it.unimib.datai.nanofaas.sdk.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class RuntimeMetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;
    private final Counter coldStarts;
    private final Counter callbackFailures;
    private final AtomicInteger inFlight = new AtomicInteger();

    public RuntimeMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
        this.coldStarts = registry.counter("runtime_cold_start");
        this.callbackFailures = registry.counter("runtime_callback_failures");
        Gauge.builder("runtime_in_flight", inFlight, AtomicInteger::get).register(registry);
        Timer.builder("runtime_invocation_duration_seconds").register(registry);
        registry.counter("runtime_invocations_total", "success", "true");
        registry.counter("runtime_invocations_total", "success", "false");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().endsWith("/invoke")) {
            chain.doFilter(request, response);
            return;
        }

        Timer.Sample sample = Timer.start(registry);
        inFlight.incrementAndGet();
        try {
            chain.doFilter(request, response);
        } finally {
            inFlight.decrementAndGet();
            sample.stop(registry.timer("runtime_invocation_duration_seconds"));
            registry.counter("runtime_invocations_total", "success", String.valueOf(response.getStatus() < 400))
                    .increment();
            if ("true".equalsIgnoreCase(response.getHeader("X-Cold-Start"))) {
                coldStarts.increment();
            }
        }
    }

    public void recordCallbackFailure() {
        callbackFailures.increment();
    }
}
