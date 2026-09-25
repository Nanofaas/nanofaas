package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import java.time.Instant;

public record DispatchResult(
        InvocationResult result,
        boolean coldStart,
        Long initDurationMs,
        Instant retryNotBefore
) {
    public DispatchResult(InvocationResult result, boolean coldStart, Long initDurationMs) {
        this(result, coldStart, initDurationMs, null);
    }

    public static DispatchResult warm(InvocationResult result) {
        return new DispatchResult(result, false, null);
    }
}
