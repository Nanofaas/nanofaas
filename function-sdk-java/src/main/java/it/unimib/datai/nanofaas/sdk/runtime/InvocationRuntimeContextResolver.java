package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvocationRuntimeContextResolver {

    private final String executionId;

    public InvocationRuntimeContextResolver(
            @Value("${EXECUTION_ID:#{systemEnvironment['EXECUTION_ID'] ?: 'test-execution'}}") String executionId) {
        this.executionId = executionId;
    }

    public InvocationRuntimeContext resolve(String headerExecutionId, String traceId) {
        String effectiveExecutionId = (headerExecutionId != null && !headerExecutionId.isBlank())
                ? headerExecutionId
                : executionId;
        return new InvocationRuntimeContext(effectiveExecutionId, traceId);
    }
}
