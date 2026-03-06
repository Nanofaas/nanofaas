package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.stereotype.Component;

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
