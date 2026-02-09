package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DispatcherRouter {
    private final LocalDispatcher localDispatcher;
    private final PoolDispatcher poolDispatcher;

    public DispatcherRouter(LocalDispatcher localDispatcher,
                            PoolDispatcher poolDispatcher) {
        this.localDispatcher = localDispatcher;
        this.poolDispatcher = poolDispatcher;
    }

    public CompletableFuture<InvocationResult> dispatchLocal(InvocationTask task) {
        return localDispatcher.dispatch(task);
    }

    public CompletableFuture<InvocationResult> dispatchPool(InvocationTask task) {
        return poolDispatcher.dispatch(task);
    }
}
