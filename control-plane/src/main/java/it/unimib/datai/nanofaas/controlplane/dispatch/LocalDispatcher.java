package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class LocalDispatcher implements Dispatcher {
    @Override
    public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
        return CompletableFuture.completedFuture(InvocationResult.success(task.request().input()));
    }
}
