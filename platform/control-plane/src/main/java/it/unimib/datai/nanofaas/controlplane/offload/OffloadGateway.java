package it.unimib.datai.nanofaas.controlplane.offload;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import reactor.core.publisher.Mono;

/**
 * Core abstraction for optional offload module integration.
 *
 * <p>Allows sync invocations to be transparently proxied to a remote nanofaas
 * instance instead of being dispatched locally. Implementations decide when to
 * offload (per-function policy or local pressure) and perform the remote call.
 */
public interface OffloadGateway {

    /** Reserved error code: remote call failed (unreachable, non-2xx, remote 404). */
    String OFFLOAD_FAILED_CODE = "OFFLOAD_FAILED";
    /** Reserved error code: remote call timed out at the gateway level. */
    String OFFLOAD_TIMEOUT_CODE = "OFFLOAD_TIMEOUT";

    boolean enabled();

    /** Strategy 3: the function's policy mandates immediate offload ({@code mode=always}). */
    boolean shouldOffloadEagerly(FunctionSpec spec);

    /** Strategies 1-2: the sync queue rejected admission (DEPTH/EST_WAIT); offload instead? */
    boolean shouldOffloadOnPressure(FunctionSpec spec, SyncQueueRejectReason reason);

    /** Effective remote base URL for the function (per-function override or global default). */
    String targetUrl(FunctionSpec spec);

    /**
     * Proxies the invocation to the remote {@code :invoke} endpoint.
     * Never errors: infrastructure failures are mapped to error results with
     * {@link #OFFLOAD_FAILED_CODE} or {@link #OFFLOAD_TIMEOUT_CODE}.
     */
    Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context);

    static OffloadGateway noOp() {
        return NoOpOffloadGateway.INSTANCE;
    }
}
