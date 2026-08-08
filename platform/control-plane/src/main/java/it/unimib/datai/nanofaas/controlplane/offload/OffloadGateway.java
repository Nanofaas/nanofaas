package it.unimib.datai.nanofaas.controlplane.offload;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import reactor.core.publisher.Mono;

/**
 * Core abstraction for optional offload module integration.
 *
 * <p>Allows sync invocations to be transparently proxied to a remote nanofaas
 * instance instead of being dispatched locally. Implementations decide when to
 * offload (per-function policy or local pressure) and perform the remote call.
 */
public interface OffloadGateway {

    /** Error code stored on the execution record when the remote call failed. */
    String OFFLOAD_FAILED_CODE = "OFFLOAD_FAILED";
    /** Error code stored on the execution record when the remote call timed out. */
    String OFFLOAD_TIMEOUT_CODE = "OFFLOAD_TIMEOUT";

    boolean enabled();

    /** Strategy 3: the function's policy mandates immediate offload ({@code mode=always}). */
    boolean shouldOffloadEagerly(FunctionSpec spec);

    /** Strategies 1-2: the sync queue rejected admission (DEPTH/EST_WAIT); offload instead? */
    boolean shouldOffloadOnPressure(FunctionSpec spec);

    /**
     * Effective remote base URL for the function (per-function override or global
     * default), normalized; null when no target is configured for this function.
     */
    String targetUrl(FunctionSpec spec);

    /**
     * Proxies the invocation to the remote {@code :invoke} endpoint within the
     * given time budget. Emits the remote function result (including remote
     * function-level errors) or errors with {@link OffloadFailedException} on
     * infrastructure failure (unreachable, non-2xx, remote 404, budget exceeded).
     */
    Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context, int timeoutBudgetMs);

    static OffloadGateway noOp() {
        return NoOpOffloadGateway.INSTANCE;
    }
}
