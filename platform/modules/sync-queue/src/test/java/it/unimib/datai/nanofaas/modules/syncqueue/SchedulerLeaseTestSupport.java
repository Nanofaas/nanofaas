package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import static org.mockito.Mockito.*;

/** Preserve each scheduler scenario's scripted availability while returning a real lease. */
public final class SchedulerLeaseTestSupport {
    private SchedulerLeaseTestSupport() { }
    public static InvocationEnqueuer enqueuer() {
        return mock(InvocationEnqueuer.class, call -> {
            if (!call.getMethod().getName().equals("tryAcquireLease")) return RETURNS_DEFAULTS.answer(call);
            InvocationEnqueuer owner = (InvocationEnqueuer) call.getMock();
            InvocationTask task = call.getArgument(0);
            if (!owner.tryAcquireSlot(task.functionName())) return null;
            var registry = new FunctionCapacityRegistry();
            var state = registry.register(task.functionName(), 1);
            return registry.tryAcquireLease(task.functionName(), state,
                    held -> owner.releaseDispatchSlot(task.functionName()));
        });
    }
}
