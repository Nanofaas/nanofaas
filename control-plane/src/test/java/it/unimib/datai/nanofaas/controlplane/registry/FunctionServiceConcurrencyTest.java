package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FunctionServiceConcurrencyTest {

    private FunctionRegistry registry;
    private FunctionService functionService;

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        functionService = new FunctionService(
                registry,
                defaults,
                null,
                ImageValidator.noOp(),
                List.of()
        );
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
                            null, null, null, null, null, null, null, null, null, null, null, null
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
                            null, null, null, null, null, null, null, null, null, null, null, null
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
                null, null, null, null, null, null, null, null, null, null, null, null
        );
        FunctionSpec spec2 = new FunctionSpec(
                "myFunc", "image2",
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        Optional<FunctionSpec> result1 = functionService.register(spec1);
        Optional<FunctionSpec> result2 = functionService.register(spec2);

        assertThat(result1).isPresent();
        assertThat(result2).isEmpty();

        // Original registration should be preserved
        assertThat(functionService.get("myFunc").orElseThrow().image()).isEqualTo("image1");
    }

    @Test
    void registerAndRemove_sameName_areSerialized() throws Exception {
        FunctionRegistry localRegistry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        KubernetesResourceManager localResourceManager = mock(KubernetesResourceManager.class);
        FunctionService localService = new FunctionService(
                localRegistry,
                defaults,
                localResourceManager,
                ImageValidator.noOp(),
                List.of()
        );

        CountDownLatch provisionStarted = new CountDownLatch(1);
        CountDownLatch allowProvision = new CountDownLatch(1);
        when(localResourceManager.provision(any())).thenAnswer(invocation -> {
            provisionStarted.countDown();
            if (!allowProvision.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish provision");
            }
            return "http://fn-svc:8080";
        });

        FunctionSpec spec = new FunctionSpec(
                "race-fn", "img:latest",
                null, null, null, null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<FunctionSpec>> registerFuture = executor.submit(() -> localService.register(spec));

            assertThat(provisionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Optional<FunctionSpec>> removeFuture = executor.submit(() -> localService.remove("race-fn"));

            Thread.sleep(150);
            assertThat(removeFuture.isDone()).isFalse();

            allowProvision.countDown();

            assertThat(registerFuture.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(removeFuture.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            executor.shutdownNow();
        }

        assertThat(localService.get("race-fn")).isEmpty();
        verify(localResourceManager, times(1)).provision(any());
        verify(localResourceManager, times(1)).deprovision("race-fn");
    }
}
