package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

/** Script scheduler races while every successful acquisition returns real owned capacity. */
public final class SchedulerLeaseTestSupport {
    private SchedulerLeaseTestSupport() { }
    public static QueuedDispatchCapacity enqueuer() { return mock(QueuedDispatchCapacity.class, new Leases()); }
    public static void allow(QueuedDispatchCapacity manager, String name, boolean... values) {
        leases(manager).availability.put(name, new Availability(values));
    }
    public static int released(QueuedDispatchCapacity manager, String name) {
        return leases(manager).released.getOrDefault(name, new AtomicInteger()).get();
    }
    private static Leases leases(QueuedDispatchCapacity manager) {
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
        private final ConcurrentHashMap<String, Availability> availability = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> released = new ConcurrentHashMap<>();
        public Object answer(InvocationOnMock call) throws Throwable {
            if (!call.getMethod().getName().equals("tryAcquireLease")) return RETURNS_DEFAULTS.answer(call);
            InvocationTask task = call.getArgument(0);
            Availability sequence = task == null ? null : availability.get(task.functionName());
            if (sequence == null || !sequence.available()) return null;
            String name = task.functionName();
            registry.register(name, 100);
            return registry.tryAcquireLease(registry.activeGeneration(name),
                    held -> released.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet());
        }
    }
}
