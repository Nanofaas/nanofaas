package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;

public record DispatchResult(
        InvocationResult result,
        boolean coldStart,
        Long initDurationMs
) {
    public static DispatchResult warm(InvocationResult result) {
        return new DispatchResult(result, false, null);
    }
}
