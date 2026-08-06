package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvocationRuntimeContextResolverTest {

    @Test
    void resolve_headerExecutionIdOverridesInjectedExecutionId() {
        InvocationRuntimeContextResolver resolver = new InvocationRuntimeContextResolver(
                new RuntimeSettings("env-exec-id", "env-trace-id", "http://callback", "handler"));

        InvocationRuntimeContext context = resolver.resolve("header-exec-id", "trace-1");

        assertEquals("header-exec-id", context.executionId());
        assertEquals("trace-1", context.traceId());
    }

    @Test
    void resolve_blankHeaderExecutionIdFallsBackToInjectedExecutionId() {
        InvocationRuntimeContextResolver resolver = new InvocationRuntimeContextResolver(
                new RuntimeSettings("env-exec-id", "env-trace-id", "http://callback", "handler"));

        InvocationRuntimeContext context = resolver.resolve("   ", "trace-2");

        assertEquals("env-exec-id", context.executionId());
        assertEquals("trace-2", context.traceId());
    }

    @Test
    void resolve_nullTraceIdFallsBackToInjectedTraceId() {
        InvocationRuntimeContextResolver resolver = new InvocationRuntimeContextResolver(
                new RuntimeSettings("env-exec-id", "env-trace-id", "http://callback", "handler"));

        InvocationRuntimeContext context = resolver.resolve(null, null);

        assertEquals("env-exec-id", context.executionId());
        assertEquals("env-trace-id", context.traceId());
    }
}
