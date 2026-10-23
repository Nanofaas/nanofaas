package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class LocalDispatcher implements Dispatcher {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LocalDispatcher.class);
    @Override
    public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
        InvocationLogs.event(LOG, task, "dispatch-local");
        return CompletableFuture.completedFuture(
                DispatchResult.warm(InvocationResult.success(task.request().input())));
    }
}
