package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.stereotype.Component;

/**
 * Normalizes request headers and environment values into a request-local runtime context.
 *
 * <p>Warm invocations may supply execution metadata in headers while one-shot mode relies on the
 * environment populated before startup. This resolver keeps the precedence rules in one place so
 * the rest of the pipeline works with a single normalized view.</p>
 */
@Component
public class InvocationRuntimeContextResolver {

    private final RuntimeSettings runtimeSettings;

    public InvocationRuntimeContextResolver(RuntimeSettings runtimeSettings) {
        this.runtimeSettings = runtimeSettings;
    }

    public InvocationRuntimeContext resolve(String headerExecutionId, String traceId) {
        String effectiveExecutionId = (headerExecutionId != null && !headerExecutionId.isBlank())
                ? headerExecutionId
                : runtimeSettings.executionId();
        return new InvocationRuntimeContext(effectiveExecutionId, traceId);
    }
}
