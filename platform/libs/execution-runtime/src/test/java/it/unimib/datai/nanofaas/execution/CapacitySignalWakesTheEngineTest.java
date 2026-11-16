package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityState;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two capacity openings that used to bypass {@link SchedulerEngine#signal()}: a concurrency
 * raised by re-registration, and the release of a direct-dispatch lease. The engine is wired the
 * way {@code SchedulerConfiguration} wires it (capacity listener and lease release both signal)
 * and runs its real worker with a frozen monotonic clock, so a park never times out: the queued
 * ticket is dispatched only if the opening itself wakes the engine.
 */
class CapacitySignalWakesTheEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private final FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
    private final CountDownLatch examinedWhileFull = new CountDownLatch(1);
    private final CountDownLatch dispatched = new CountDownLatch(1);
    private SchedulerEngine engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void aConcurrencyRaiseOnReRegistrationWakesTheEngine() throws Exception {
        registry.register("fn", 1);
        DispatchLease running = registry.tryAcquireLease("fn", 1);
        startEngineWithQueuedTicket();

        registry.register("fn", 2);

        assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
        running.release();
    }

    @Test
    void releasingADirectDispatchLeaseWakesTheEngine() throws Exception {
        registry.register("fn", 1);
        DispatchLease direct = registry.tryAcquireLease("fn", 1);
        startEngineWithQueuedTicket();

        direct.release();

        assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void startEngineWithQueuedTicket() throws InterruptedException {
        EngineDispatch dispatch = mock(EngineDispatch.class);
        AtomicReference<SchedulerEngine> self = new AtomicReference<>();
        when(dispatch.tryAcquire(any())).thenAnswer(invocation -> registry.tryAcquireLease(
                ((SchedulingTicket) invocation.getArgument(0)).generation(), held -> self.get().signal()));
        doAnswer(invocation -> {
            dispatched.countDown();
            return null;
        }).when(dispatch).submit(any());
        EngineReadiness readiness = generation -> {
            FunctionCapacityState state = registry.state(generation.functionName());
            boolean ready = state != null && generation.equals(state.generation()) && state.canDispatch();
            if (!ready) {
                examinedWhileFull.countDown();
            }
            return ready;
        };
        SchedulingStrategy strategy = new SchedulingStrategy() {
            @Override
            public String id() {
                return "single-ticket";
            }

            @Override
            public SchedulingIndex newIndex() {
                return new SingleTicketIndex();
            }
        };
        engine = new SchedulerEngine(new PendingWorkStore(16), new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, readiness,
                generation -> generation.equals(registry.activeGeneration(generation.functionName())),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L);
        self.set(engine);
        registry.addCapacityListener(name -> engine.signal());

        FunctionGeneration generation = registry.activeGeneration("fn");
        SchedulingTicket ticket = new SchedulingTicket(new TicketId("e1", 1), generation, 0, NOW, NOW, null);
        assertThat(engine.enqueue(new PendingEntry(ticket,
                new InvocationTask("e1", "fn", null, null, null, null, NOW, 1, InvocationKind.SYNC)))).isTrue();
        engine.start();
        // The worker has found the function full: from here only a wake makes it look again.
        assertThat(examinedWhileFull.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** The one queued ticket; the policy is irrelevant to the wake under test. */
    private static final class SingleTicketIndex implements SchedulingIndex {
        private SchedulingTicket held;

        @Override
        public void add(SchedulingTicket ticket) {
            held = ticket;
        }

        @Override
        public void remove(TicketId id) {
            if (held != null && held.id().equals(id)) {
                held = null;
            }
        }

        @Override
        public SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable) {
            return held != null && runnable.test(held.generation()) ? held : null;
        }

        @Override
        public void defer(TicketId id) {
        }

        @Override
        public int size() {
            return held == null ? 0 : 1;
        }

        @Override
        public void clear() {
            held = null;
        }
    }
}
