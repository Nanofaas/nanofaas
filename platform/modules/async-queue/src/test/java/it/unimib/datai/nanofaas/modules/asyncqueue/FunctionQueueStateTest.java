package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionQueueStateTest {
    private it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry registry =
            new it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry();
    private final AtomicLong lastHold = new AtomicLong();
    private it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership acquire() {
        return registry.tryAcquireLease(registry.activeGeneration("fn"), lastHold::set);
    }

    @Test
    void fifoPairingPreservesAggregateTotalButNotTheRealPerRequestMaximum() {
        AtomicLong nanoTime = new AtomicLong();
        registry = new it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry(nanoTime::get);
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 2));

        var first = acquire();
        nanoTime.set(10);
        var second = acquire();

        // B completes at 20 and A at 100: their real holds are 10 and 100.
        nanoTime.set(20);
        second.release();
        long firstRecordedHold = lastHold.get();
        nanoTime.set(100);
        first.release();
        long secondRecordedHold = lastHold.get();

        assertThat(firstRecordedHold).isEqualTo(20);
        assertThat(secondRecordedHold).isEqualTo(90);
        assertThat(firstRecordedHold + secondRecordedHold).isEqualTo(110);
        assertThat(Math.max(firstRecordedHold, secondRecordedHold)).isEqualTo(90);
    }

    @Test
    void tryAcquireSlot_underLimit_returnsTrue() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 2));

        assertThat(acquire()).isNotNull();
        assertThat(acquire()).isNotNull();
    }

    @Test
    void tryAcquireSlot_atLimit_returnsFalse() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 2));

        acquire();
        acquire();

        assertThat(acquire()).isNull();
    }

    @Test
    void releaseSlot_afterAcquire_allowsNewAcquire() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 1));

        var lease = acquire();
        assertThat(acquire()).isNull();

        lease.release();
        assertThat(acquire()).isNotNull();
    }

    @Test
    void tryAcquireSlot_underConcurrentLoad_neverExceedsLimit() throws Exception {
        int concurrencyLimit = 5;
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", concurrencyLimit));

        int numThreads = 50;
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        var lease = acquire();
                        if (lease != null) {
                            int concurrent = currentConcurrent.incrementAndGet();
                            maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));

                            // Simulate some work
                            Thread.yield();

                            currentConcurrent.decrementAndGet();
                            lease.release();
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            t.start();
        }

        startLatch.countDown();
        endLatch.await();

        // Max concurrent should NEVER exceed the limit
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(concurrencyLimit);
    }

    @Test
    void inFlight_tracksCorrectly() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 10));

        assertThat(state.inFlight()).isZero();

        var first = acquire();
        assertThat(state.inFlight()).isEqualTo(1);

        var second = acquire();
        assertThat(state.inFlight()).isEqualTo(2);

        first.release();
        assertThat(state.inFlight()).isEqualTo(1);

        second.release();
        assertThat(state.inFlight()).isZero();
    }

    @Test
    void offer_andPoll_workCorrectly() {
        FunctionQueueState state = new FunctionQueueState("fn", 2, registry.register("fn", 1));

        InvocationTask task1 = createTask("exec1");
        InvocationTask task2 = createTask("exec2");
        InvocationTask task3 = createTask("exec3");

        assertThat(state.offer(task1)).isTrue();
        assertThat(state.offer(task2)).isTrue();
        assertThat(state.offer(task3)).isFalse();  // Queue full

        assertThat(state.poll()).isEqualTo(task1);
        assertThat(state.poll()).isEqualTo(task2);
        assertThat(state.poll()).isNull();
    }

    @Test
    void dispatchReservationPreventsConcurrentOfferFromDisplacingBackpressuredTask() {
        FunctionQueueState state = new FunctionQueueState("fn", 1, registry.register("fn", 1));
        InvocationTask first = createTask("first");
        InvocationTask second = createTask("second");
        assertThat(state.offer(first)).isTrue();

        InvocationTask dispatching = state.pollForDispatch();

        assertThat(state.offer(second)).isFalse();
        assertThat(state.requeueAfterInputBackpressure(dispatching)).isTrue();
        assertThat(state.poll()).isSameAs(first);
    }

    @Test
    void queued_returnsCorrectCount() {
        FunctionQueueState state = new FunctionQueueState("fn", 10, registry.register("fn", 1));

        assertThat(state.queued()).isZero();

        state.offer(createTask("exec1"));
        assertThat(state.queued()).isEqualTo(1);

        state.offer(createTask("exec2"));
        assertThat(state.queued()).isEqualTo(2);

        state.poll();
        assertThat(state.queued()).isEqualTo(1);
    }

    @Test
    void setEffectiveConcurrency_limitsAcquireWithoutChangingConfigured() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 6));
        registry.setEffectiveConcurrency("fn", 2);

        assertThat(state.configuredConcurrency()).isEqualTo(6);
        assertThat(state.effectiveConcurrency()).isEqualTo(2);
        assertThat(acquire()).isNotNull();
        assertThat(acquire()).isNotNull();
        assertThat(acquire()).isNull();
    }

    @Test
    void configuredConcurrency_reducesEffectiveWhenLowered() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, registry.register("fn", 6));
        registry.setEffectiveConcurrency("fn", 5);
        registry.register("fn", 3);

        assertThat(state.configuredConcurrency()).isEqualTo(3);
        assertThat(state.effectiveConcurrency()).isEqualTo(3);
    }

    private InvocationTask createTask(String executionId) {
        return createTask(executionId, InvocationKind.SYNC);
    }

    private InvocationTask createTask(String executionId, InvocationKind kind) {
        return new InvocationTask(
                executionId,
                "testFunc",
                null,
                null,
                null,
                null,
                null,
                1,
                kind
        );
    }

    @Test
    void theBacklogSaysHowMuchOfItselfNobodyIsWaitingFor() {
        FunctionQueueState state = new FunctionQueueState("mixed", 10, registry.register("mixed", 1));

        state.offer(createTask("s1", InvocationKind.SYNC));
        state.offer(createTask("a1", InvocationKind.ASYNC));
        state.offer(createTask("a2", InvocationKind.ASYNC));

        assertThat(state.queued()).isEqualTo(3);
        assertThat(state.queued(InvocationKind.SYNC)).isEqualTo(1);
        assertThat(state.queued(InvocationKind.ASYNC)).isEqualTo(2);

        state.poll();  // FIFO: the sync one leaves first
        assertThat(state.queued(InvocationKind.SYNC)).isZero();
        assertThat(state.queued(InvocationKind.ASYNC)).isEqualTo(2);

        // A deregistration drains the rest; the split has to come back to zero with it,
        // or a removed function leaves a gauge stuck above zero forever.
        state.closeAndDrainQueued();
        assertThat(state.queued(InvocationKind.SYNC)).isZero();
        assertThat(state.queued(InvocationKind.ASYNC)).isZero();
    }

}
