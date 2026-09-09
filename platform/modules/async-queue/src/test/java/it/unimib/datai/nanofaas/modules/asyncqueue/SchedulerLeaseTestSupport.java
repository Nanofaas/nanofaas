package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import static org.mockito.Mockito.*;

final class SchedulerLeaseTestSupport {
    private SchedulerLeaseTestSupport() { }
    static QueueManager queueManager() {
        return mock(QueueManager.class, call -> {
            if (!call.getMethod().getName().equals("tryAcquireLease")) return RETURNS_DEFAULTS.answer(call);
            QueueManager owner = (QueueManager) call.getMock();
            String name = call.getArgument(0);
            FunctionQueueState queued = call.getArgument(1);
            if (!queued.tryAcquireSlot()) return null;
            var registry = new FunctionCapacityRegistry();
            var state = registry.register(name, 1);
            return registry.tryAcquireLease(name, state, held -> owner.releaseSlot(name, queued));
        });
    }
}
