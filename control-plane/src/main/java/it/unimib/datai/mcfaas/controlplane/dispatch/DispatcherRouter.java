package it.unimib.datai.mcfaas.controlplane.dispatch;

import it.unimib.datai.mcfaas.common.model.InvocationResult;
import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DispatcherRouter {
    private final LocalDispatcher localDispatcher;
    private final KubernetesDispatcher kubernetesDispatcher;
    private final PoolDispatcher poolDispatcher;

    public DispatcherRouter(LocalDispatcher localDispatcher,
                            KubernetesDispatcher kubernetesDispatcher,
                            PoolDispatcher poolDispatcher) {
        this.localDispatcher = localDispatcher;
        this.kubernetesDispatcher = kubernetesDispatcher;
        this.poolDispatcher = poolDispatcher;
    }

    public CompletableFuture<InvocationResult> dispatchLocal(InvocationTask task) {
        return localDispatcher.dispatch(task);
    }

    public CompletableFuture<InvocationResult> dispatchRemote(InvocationTask task) {
        return kubernetesDispatcher.dispatch(task);
    }

    public CompletableFuture<InvocationResult> dispatchPool(InvocationTask task) {
        return poolDispatcher.dispatch(task);
    }
}
