package com.mcfaas.controlplane.queue;

import com.mcfaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionQueueStateTest {

    @Test
    void tryAcquireSlot_underLimit_returnsTrue() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, 2);

        assertThat(state.tryAcquireSlot()).isTrue();
        assertThat(state.tryAcquireSlot()).isTrue();
    }

    @Test
    void tryAcquireSlot_atLimit_returnsFalse() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, 2);

        state.tryAcquireSlot();
        state.tryAcquireSlot();

        assertThat(state.tryAcquireSlot()).isFalse();
    }

    @Test
    void releaseSlot_afterAcquire_allowsNewAcquire() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, 1);

        state.tryAcquireSlot();
        assertThat(state.tryAcquireSlot()).isFalse();

        state.releaseSlot();
        assertThat(state.tryAcquireSlot()).isTrue();
    }

    @Test
    void tryAcquireSlot_underConcurrentLoad_neverExceedsLimit() throws Exception {
        int concurrencyLimit = 5;
        FunctionQueueState state = new FunctionQueueState("fn", 100, concurrencyLimit);

        int numThreads = 50;
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        if (state.tryAcquireSlot()) {
                            int concurrent = currentConcurrent.incrementAndGet();
                            maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));

                            // Simulate some work
                            Thread.yield();

                            currentConcurrent.decrementAndGet();
                            state.releaseSlot();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        startLatch.countDown();
        endLatch.await();

        // Max concurrent should NEVER exceed the limit
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(concurrencyLimit);
    }

    @Test
    void inFlight_tracksCorrectly() {
        FunctionQueueState state = new FunctionQueueState("fn", 100, 10);

        assertThat(state.inFlight()).isEqualTo(0);

        state.tryAcquireSlot();
        assertThat(state.inFlight()).isEqualTo(1);

        state.tryAcquireSlot();
        assertThat(state.inFlight()).isEqualTo(2);

        state.releaseSlot();
        assertThat(state.inFlight()).isEqualTo(1);

        state.releaseSlot();
        assertThat(state.inFlight()).isEqualTo(0);
    }

    @Test
    void offer_andPoll_workCorrectly() {
        FunctionQueueState state = new FunctionQueueState("fn", 2, 1);

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
    void queued_returnsCorrectCount() {
        FunctionQueueState state = new FunctionQueueState("fn", 10, 1);

        assertThat(state.queued()).isEqualTo(0);

        state.offer(createTask("exec1"));
        assertThat(state.queued()).isEqualTo(1);

        state.offer(createTask("exec2"));
        assertThat(state.queued()).isEqualTo(2);

        state.poll();
        assertThat(state.queued()).isEqualTo(1);
    }

    private InvocationTask createTask(String executionId) {
        return new InvocationTask(
                executionId,
                "testFunc",
                null,
                null,
                null,
                null,
                null,
                1
        );
    }
}
