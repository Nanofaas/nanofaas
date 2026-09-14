package it.unimib.datai.nanofaas.sdk.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nanofaas.metrics.profile", havingValue = "soak")
final class RuntimeSoakMetrics {
    RuntimeSoakMetrics(MeterRegistry registry, HandlerExecutor handlers, CallbackDispatcher callbacks) {
        Gauge.builder("runtime_active_handlers", handlers, HandlerExecutor::activeHandlerCount)
                .register(registry);
        Gauge.builder("runtime_pending_callbacks", callbacks, CallbackDispatcher::pendingCallbackCount)
                .register(registry);
        Gauge.builder("runtime_pending_callback_bytes", callbacks, CallbackDispatcher::pendingCallbackBytes)
                .register(registry);
    }
}
