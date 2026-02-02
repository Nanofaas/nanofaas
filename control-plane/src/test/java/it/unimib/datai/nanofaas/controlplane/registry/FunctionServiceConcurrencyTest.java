package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionServiceConcurrencyTest {

    @Mock
    private QueueManager queueManager;

    private FunctionRegistry registry;
    private FunctionService functionService;

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        functionService = new FunctionService(registry, queueManager, defaults);

        when(queueManager.getOrCreate(any())).thenReturn(null);
    }

    @Test
    void register_withSameName_onlyOneSucceeds() throws Exception {
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        List<FunctionSpec> registeredSpecs = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    FunctionSpec spec = new FunctionSpec(
                            "myFunc",  // Same name
                            "image-" + threadId,
                            null, null, null, null, null, null, null, null, null, null, null
                    );

                    Optional<FunctionSpec> result = functionService.register(spec);
                    if (result.isPresent()) {
                        successCount.incrementAndGet();
                        registeredSpecs.add(result.get());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // Only ONE should succeed
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(registeredSpecs).hasSize(1);

        // The registered function should be in the registry
        FunctionSpec registered = functionService.get("myFunc").orElseThrow();
        assertThat(registered.image()).isEqualTo(registeredSpecs.get(0).image());
    }

    @Test
    void register_withDifferentNames_allSucceed() throws Exception {
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    FunctionSpec spec = new FunctionSpec(
                            "func-" + threadId,  // Different names
                            "image-" + threadId,
                            null, null, null, null, null, null, null, null, null, null, null
                    );

                    Optional<FunctionSpec> result = functionService.register(spec);
                    if (result.isPresent()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // ALL should succeed
        assertThat(successCount.get()).isEqualTo(numThreads);
        assertThat(functionService.list()).hasSize(numThreads);
    }

    @Test
    void register_existingFunction_returnsEmpty() {
        FunctionSpec spec1 = new FunctionSpec(
                "myFunc", "image1",
                null, null, null, null, null, null, null, null, null, null, null
        );
        FunctionSpec spec2 = new FunctionSpec(
                "myFunc", "image2",
                null, null, null, null, null, null, null, null, null, null, null
        );

        Optional<FunctionSpec> result1 = functionService.register(spec1);
        Optional<FunctionSpec> result2 = functionService.register(spec2);

        assertThat(result1).isPresent();
        assertThat(result2).isEmpty();

        // Original registration should be preserved
        assertThat(functionService.get("myFunc").orElseThrow().image()).isEqualTo("image1");
    }
}
