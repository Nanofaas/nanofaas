package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

final class SchedulerLeaseTestSupport {
    private SchedulerLeaseTestSupport() { }
    static QueueManager queueManager() { return mock(QueueManager.class, new Leases()); }
    static void allow(QueueManager manager, FunctionQueueState state, boolean... available) {
        leases(manager).availability.put(state, new Availability(available));
    }
    static int released(QueueManager manager) { return leases(manager).released.get(); }
    private static Leases leases(QueueManager manager) {
        return (Leases) mockingDetails(manager).getMockCreationSettings().getDefaultAnswer();
    }
    private static final class Availability {
        private final boolean[] values;
        private final AtomicInteger next = new AtomicInteger();
        Availability(boolean[] values) { this.values = values; }
        boolean available() { return values[Math.min(next.getAndIncrement(), values.length - 1)]; }
    }
    private static final class Leases implements Answer<Object> {
        private final FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        private final ConcurrentHashMap<FunctionQueueState, Availability> availability = new ConcurrentHashMap<>();
        private final AtomicInteger released = new AtomicInteger();
        public Object answer(InvocationOnMock call) throws Throwable {
            if (!call.getMethod().getName().equals("tryAcquireLease")) return RETURNS_DEFAULTS.answer(call);
            FunctionQueueState state = call.getArgument(1);
            Availability sequence = state == null ? null : availability.get(state);
            if (sequence == null || !sequence.available()) return null;
            String name = call.getArgument(0);
            registry.register(name, 100);
            return registry.tryAcquireLease(registry.activeGeneration(name), held -> released.incrementAndGet());
        }
    }
}
