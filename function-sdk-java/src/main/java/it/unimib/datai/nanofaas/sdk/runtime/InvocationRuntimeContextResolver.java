package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.stereotype.Component;

/**
 * Normalizes request and startup metadata into the effective invocation context.
 *
 * <p>This resolver exists because the runtime has two sources of truth for execution metadata:
 * request headers in warm mode and env vars in one-shot mode. Keeping the precedence rules here
 * avoids duplicating header-vs-env logic in the controller or the tracing filter.</p>
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
