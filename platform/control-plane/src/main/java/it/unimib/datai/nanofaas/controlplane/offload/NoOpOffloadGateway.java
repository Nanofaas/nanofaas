package it.unimib.datai.nanofaas.controlplane.offload;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import reactor.core.publisher.Mono;

final class NoOpOffloadGateway implements OffloadGateway {

    static final NoOpOffloadGateway INSTANCE = new NoOpOffloadGateway();

    private NoOpOffloadGateway() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean shouldOffloadEagerly(FunctionSpec spec) {
        return false;
    }

    @Override
    public boolean shouldOffloadOnPressure(FunctionSpec spec, SyncQueueRejectReason reason) {
        return false;
    }

    @Override
    public String targetUrl(FunctionSpec spec) {
        return null;
    }

    @Override
    public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context) {
        return Mono.just(InvocationResult.error(OFFLOAD_FAILED_CODE, "offload module not installed"));
    }
}
