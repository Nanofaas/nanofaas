package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The active/retiring/closed protocol every per-function resource owner shares
 * (ADR 0001 §8.2). The risk covered here is the one invariant I7 names: retired state
 * must survive exactly as long as the resources it still owns, and the transition that
 * lets the owner drop it must be reported to one caller only.
 */
class GenerationLifecycleTest {

    @Test
    void aFreshGenerationIsActiveAndHoldsNothing() {
        GenerationLifecycle lifecycle = new GenerationLifecycle();

        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.ACTIVE);
        assertThat(lifecycle.isActive()).isTrue();
        assertThat(lifecycle.retained()).isZero();
    }

    @Test
    void retiringAnEmptyGenerationClosesItAtOnce() {
        GenerationLifecycle lifecycle = new GenerationLifecycle();

        assertThat(lifecycle.retire()).isTrue();

        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.CLOSED);
        assertThat(lifecycle.phase().isClosed()).isTrue();
    }

    @Test
    void retiredStateSurvivesUntilItsLastResourceComesBack() {
        GenerationLifecycle lifecycle = new GenerationLifecycle();
        assertThat(lifecycle.retain()).isTrue();
        assertThat(lifecycle.retain()).isTrue();

        assertThat(lifecycle.retire()).as("still holds two resources").isFalse();
        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.RETIRING);

        assertThat(lifecycle.release()).as("one resource still out").isFalse();
        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.RETIRING);

        assertThat(lifecycle.release()).as("the last release closes it").isTrue();
        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.CLOSED);
    }

    @Test
    void onlyAnActiveGenerationAdmitsNewWork() {
        GenerationLifecycle retiring = new GenerationLifecycle();
        retiring.retain();
        retiring.retire();

        assertThat(retiring.retain()).isFalse();
        assertThat(retiring.retainIfBelow(Integer.MAX_VALUE)).isFalse();
        assertThat(retiring.retained()).isEqualTo(1);

        GenerationLifecycle closed = new GenerationLifecycle();
        closed.retire();

        assertThat(closed.retain()).as("a closed generation is never resurrected").isFalse();
        assertThat(closed.retained()).isZero();
    }

    @Test
    void theClosingTransitionIsReportedExactlyOnce() {
        GenerationLifecycle lifecycle = new GenerationLifecycle();
        lifecycle.retain();
        lifecycle.retire();

        assertThat(lifecycle.release()).isTrue();
        assertThat(lifecycle.release()).as("a duplicate release closes nothing again").isFalse();
        assertThat(lifecycle.retire()).as("retiring a closed generation is a no-op").isFalse();
        assertThat(lifecycle.retained()).isZero();
    }

    @Test
    void aReleaseOfNothingNeverGoesNegative() {
        GenerationLifecycle lifecycle = new GenerationLifecycle();

        assertThat(lifecycle.release()).isFalse();

        assertThat(lifecycle.retained()).isZero();
        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.ACTIVE);
    }

    @Test
    void retainIfBelowEnforcesTheOwnersCeilingTogetherWithTheIncrement() {
        GenerationLifecycle lifecycle = new GenerationLifecycle();

        assertThat(lifecycle.retainIfBelow(2)).isTrue();
        assertThat(lifecycle.retainIfBelow(2)).isTrue();
        assertThat(lifecycle.retainIfBelow(2)).isFalse();

        assertThat(lifecycle.retained()).isEqualTo(2);
        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.ACTIVE);
    }

    @Test
    void underContentionExactlyOneReleaseSeesTheGenerationClose() throws Exception {
        GenerationLifecycle lifecycle = new GenerationLifecycle();
        int resources = 32;
        for (int i = 0; i < resources; i++) {
            assertThat(lifecycle.retain()).isTrue();
        }
        lifecycle.retire();

        AtomicInteger closings = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(resources);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(resources);
        try {
            for (int i = 0; i < resources; i++) {
                workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    if (lifecycle.release()) {
                        closings.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            workers.shutdownNow();
        }

        assertThat(closings).hasValue(1);
        assertThat(lifecycle.retained()).isZero();
        assertThat(lifecycle.phase()).isEqualTo(GenerationPhase.CLOSED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
