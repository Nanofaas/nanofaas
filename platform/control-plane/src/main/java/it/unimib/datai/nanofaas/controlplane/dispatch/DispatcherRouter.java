package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DispatcherRouter {
    private final LocalDispatcher localDispatcher;
    private final ExternalDispatcher poolDispatcher;

    public DispatcherRouter(LocalDispatcher localDispatcher,
                            ExternalDispatcher poolDispatcher) {
        this.localDispatcher = localDispatcher;
        this.poolDispatcher = poolDispatcher;
    }

    public CompletableFuture<DispatchResult> dispatchLocal(InvocationTask task) {
        return localDispatcher.dispatch(task);
    }

    public CompletableFuture<DispatchResult> dispatchExternal(InvocationTask task) {
        return poolDispatcher.dispatch(task);
    }
}
