package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.execution.OverridableExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class InvocationExecutionFactoryTest {

    /**
     * A claim that is never published nor abandoned (an admission path that leaked it) must not
     * hold its duplicates forever: each one parks a boundedElastic thread, and the pending key
     * only expires after max-lifetime (30 minutes by default).
     */
    @Test
    void createOrReuseExecution_stopsWaitingOnAClaimThatIsNeverPublished() {
        IdempotencyStore idempotencyStore = ExecutionFixtures.keys();
        InvocationExecutionFactory factory = ServiceFixtures.factory(
                ExecutionFixtures.store(idempotencyStore), idempotencyStore, new Metrics(new SimpleMeterRegistry()),
                "leaked-claim-fn");
        FunctionSpec spec = functionSpec("leaked-claim-fn");
        idempotencyStore.acquireOrGet("leaked-claim-fn", "leaked-key");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<InvocationExecutionFactory.ExecutionLookup> duplicate = executor.submit(() ->
                    factory.createOrReuseExecution("leaked-claim-fn", spec,
                            new InvocationRequest("payload", Map.of()), "leaked-key", null, InvocationKind.SYNC));

            // failsWithin alone also accepts a TimeoutException: demand a real, completed failure.
            assertThat(duplicate).failsWithin(Duration.ofSeconds(3))
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createOrReuseExecution_sameKeyWaitsForPendingClaimThenReturnsPublishedExecution() throws Exception {
        IdempotencyStore idempotencyStore = ExecutionFixtures.keys();
        BlockingExecutionStore executionStore = new BlockingExecutionStore(idempotencyStore);
        InvocationExecutionFactory factory = ServiceFixtures.factory(executionStore, idempotencyStore,
                new Metrics(new SimpleMeterRegistry()), "pending-idem-fn");
        FunctionSpec spec = functionSpec("pending-idem-fn");
        InvocationRequest request = new InvocationRequest("payload", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InvocationExecutionFactory.ExecutionLookup> first = executor.submit(() ->
                    factory.createOrReuseExecution("pending-idem-fn", spec, request, "same-key", "trace-1", InvocationKind.SYNC));

            executionStore.awaitFirstPutStarted();

            Future<InvocationExecutionFactory.ExecutionLookup> second = executor.submit(() ->
                    factory.createOrReuseExecution("pending-idem-fn", spec, request, "same-key", "trace-2", InvocationKind.SYNC));

            // the second invocation must still be waiting on the pending claim
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(second).isNotDone());

            executionStore.allowFirstPutToComplete();
            InvocationExecutionFactory.ExecutionLookup firstLookup = first.get(1, TimeUnit.SECONDS);
            firstLookup.publishAdmission();

            InvocationExecutionFactory.ExecutionLookup secondLookup = second.get(1, TimeUnit.SECONDS);
            assertThat(secondLookup.isNew()).isFalse();
            assertThat(secondLookup.executionRecord().executionId()).isEqualTo(firstLookup.executionRecord().executionId());
        } finally {
            executionStore.allowFirstPutToComplete();
            executor.shutdownNow();
        }
    }

    @Test
    void createOrReuseExecution_doesNotUseThreadOnSpinWait() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactory.java"
        );
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of(
                    "control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactory.java"
            );
        }
        String source = Files.readString(sourcePath);

        assertThat(source).doesNotContain("Thread.onSpinWait()");
    }

    @Test
    void createOrReuseExecution_whenInterruptedWhileWaitingForPendingClaim_abortsAndPreservesInterrupt() {
        IdempotencyStore idempotencyStore = ExecutionFixtures.keys(Duration.ofSeconds(5));
        ExecutionStore executionStore = ExecutionFixtures.store(idempotencyStore);
        InvocationExecutionFactory factory = ServiceFixtures.factory(executionStore, idempotencyStore,
                new Metrics(new SimpleMeterRegistry()), "interrupted-pending-fn");
        FunctionSpec spec = functionSpec("interrupted-pending-fn");
        idempotencyStore.acquireOrGet("interrupted-pending-fn", "same-key");

        Thread.currentThread().interrupt();
        try {
            InvocationRequest request = new InvocationRequest("payload", Map.of());
            assertThatThrownBy(() -> factory.createOrReuseExecution(
                    "interrupted-pending-fn",
                    spec,
                    request,
                    "same-key",
                    null
            ,
InvocationKind.SYNC
)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static FunctionSpec functionSpec(String functionName) {
        return new FunctionSpec(
                functionName,
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                1,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
    }

    private static final class BlockingExecutionStore extends OverridableExecutionStore {
        private final CountDownLatch firstPutStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstPutToComplete = new CountDownLatch(1);
        private final AtomicBoolean blockNextPut = new AtomicBoolean(true);

        BlockingExecutionStore(IdempotencyStore keys) {
            super(keys);
        }

        @Override
        public void put(ExecutionRecord executionRecord) {
            if (blockNextPut.compareAndSet(true, false)) {
                firstPutStarted.countDown();
                try {
                    assertThat(allowFirstPutToComplete.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking first execution put", ex);
                }
            }
            super.put(executionRecord);
        }

        void awaitFirstPutStarted() throws InterruptedException {
            assertThat(firstPutStarted.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void allowFirstPutToComplete() {
            allowFirstPutToComplete.countDown();
        }
    }
}
