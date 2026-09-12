package it.unimib.datai.nanofaas.controlplane.offload;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
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
    public boolean shouldOffloadOnPressure(FunctionSpec spec) {
        return false;
    }

    @Override
    public String targetUrl(FunctionSpec spec) {
        return null;
    }

    @Override
    public Mono<InvocationResult> invokeRemote(InvocationTask task, OffloadTrigger trigger, OffloadContext context, int timeoutBudgetMs) {
        return Mono.error(new OffloadFailedException(null, false, "offload module not installed"));
    }
}
