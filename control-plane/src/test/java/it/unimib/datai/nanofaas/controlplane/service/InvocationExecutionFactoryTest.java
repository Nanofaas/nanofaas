package it.unimib.datai.nanofaas.controlplane.service;

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

class InvocationExecutionFactoryTest {

    @Test
    void createOrReuseExecution_sameKeyWaitsForPendingClaimThenReturnsPublishedExecution() throws Exception {
        BlockingExecutionStore executionStore = new BlockingExecutionStore();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        InvocationExecutionFactory factory = new InvocationExecutionFactory(executionStore, idempotencyStore);
        FunctionSpec spec = functionSpec("pending-idem-fn");
        InvocationRequest request = new InvocationRequest("payload", Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InvocationExecutionFactory.ExecutionLookup> first = executor.submit(() ->
                    factory.createOrReuseExecution("pending-idem-fn", spec, request, "same-key", "trace-1"));

            executionStore.awaitFirstPutStarted();

            Future<InvocationExecutionFactory.ExecutionLookup> second = executor.submit(() ->
                    factory.createOrReuseExecution("pending-idem-fn", spec, request, "same-key", "trace-2"));

            Thread.sleep(50);
            assertThat(second).isNotDone();

            executionStore.allowFirstPutToComplete();
            InvocationExecutionFactory.ExecutionLookup firstLookup = first.get(1, TimeUnit.SECONDS);
            firstLookup.publishAdmission();

            InvocationExecutionFactory.ExecutionLookup secondLookup = second.get(1, TimeUnit.SECONDS);
            assertThat(secondLookup.isNew()).isFalse();
            assertThat(secondLookup.record().executionId()).isEqualTo(firstLookup.record().executionId());
        } finally {
            executionStore.allowFirstPutToComplete();
            executor.shutdownNow();
            executionStore.shutdown();
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
        ExecutionStore executionStore = new ExecutionStore();
        IdempotencyStore idempotencyStore = new IdempotencyStore(Duration.ofSeconds(5));
        InvocationExecutionFactory factory = new InvocationExecutionFactory(executionStore, idempotencyStore);
        FunctionSpec spec = functionSpec("interrupted-pending-fn");
        idempotencyStore.acquireOrGet("interrupted-pending-fn", "same-key");

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> factory.createOrReuseExecution(
                    "interrupted-pending-fn",
                    spec,
                    new InvocationRequest("payload", Map.of()),
                    "same-key",
                    null
            )).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            executionStore.shutdown();
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

    private static final class BlockingExecutionStore extends ExecutionStore {
        private final CountDownLatch firstPutStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstPutToComplete = new CountDownLatch(1);
        private final AtomicBoolean blockNextPut = new AtomicBoolean(true);

        @Override
        public void put(ExecutionRecord record) {
            if (blockNextPut.compareAndSet(true, false)) {
                firstPutStarted.countDown();
                try {
                    assertThat(allowFirstPutToComplete.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking first execution put", ex);
                }
            }
            super.put(record);
        }

        void awaitFirstPutStarted() throws InterruptedException {
            assertThat(firstPutStarted.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void allowFirstPutToComplete() {
            allowFirstPutToComplete.countDown();
        }
    }
}
