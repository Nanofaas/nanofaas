package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
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
                ImageValidator.noOp(),
                List.of(),
                new DeploymentProviderResolver(List.of(), new DeploymentProperties(null))
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
                            null, null, null, null, null, null, null, null, ExecutionMode.LOCAL, null, null, null
                    );

                    Optional<RegisteredFunction> result = functionService.register(spec);
                    if (result.isPresent()) {
                        successCount.incrementAndGet();
                        registeredSpecs.add(result.get().spec());
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
                            null, null, null, null, null, null, null, null, ExecutionMode.LOCAL, null, null, null
                    );

                    Optional<RegisteredFunction> result = functionService.register(spec);
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
                null, null, null, null, null, null, null, null, ExecutionMode.LOCAL, null, null, null
        );
        FunctionSpec spec2 = new FunctionSpec(
                "myFunc", "image2",
                null, null, null, null, null, null, null, null, ExecutionMode.LOCAL, null, null, null
        );

        Optional<RegisteredFunction> result1 = functionService.register(spec1);
        Optional<RegisteredFunction> result2 = functionService.register(spec2);

        assertThat(result1).isPresent();
        assertThat(result2).isEmpty();

        // Original registration should be preserved
        assertThat(functionService.get("myFunc").orElseThrow().image()).isEqualTo("image1");
    }

    @Test
    void registerAndRemove_sameName_areSerialized() throws Exception {
        FunctionRegistry localRegistry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        ManagedDeploymentProvider localProvider = provider();
        FunctionService localService = new FunctionService(
                localRegistry,
                defaults,
                ImageValidator.noOp(),
                List.of(),
                resolver(localProvider)
        );

        CountDownLatch provisionStarted = new CountDownLatch(1);
        CountDownLatch allowProvision = new CountDownLatch(1);
        when(localProvider.provision(any())).thenAnswer(invocation -> {
            provisionStarted.countDown();
            if (!allowProvision.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish provision");
            }
            return new ProvisionResult("http://fn-svc:8080", "k8s");
        });

        FunctionSpec spec = new FunctionSpec(
                "race-fn", "img:latest",
                null, null, null, null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<RegisteredFunction>> registerFuture = executor.submit(() -> localService.register(spec));

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
        verify(localProvider, times(1)).provision(any());
        verify(localProvider, times(1)).deprovision("race-fn");
    }

    @Test
    void register_deploymentNotVisibleUntilProvisioningProducesFinalSpec() throws Exception {
        FunctionRegistry localRegistry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        ManagedDeploymentProvider localProvider = provider();
        FunctionService localService = new FunctionService(
                localRegistry,
                defaults,
                ImageValidator.noOp(),
                List.of(),
                resolver(localProvider)
        );

        CountDownLatch provisionStarted = new CountDownLatch(1);
        CountDownLatch allowProvision = new CountDownLatch(1);
        when(localProvider.provision(any())).thenAnswer(invocation -> {
            provisionStarted.countDown();
            if (!allowProvision.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish provision");
            }
            return new ProvisionResult("http://fn-svc:8080", "k8s");
        });

        FunctionSpec spec = new FunctionSpec(
                "deploy-fn", "img:latest",
                null, null, null, null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<RegisteredFunction>> registerFuture = executor.submit(() -> localService.register(spec));

            assertThat(provisionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);

            assertThat(localService.get("deploy-fn")).isEmpty();
            assertThat(localService.list()).isEmpty();
            assertThat(localRegistry.get("deploy-fn")).isEmpty();

            allowProvision.countDown();

            Optional<RegisteredFunction> registered = registerFuture.get(5, TimeUnit.SECONDS);
            assertThat(registered).isPresent();
            assertThat(registered.get().spec().endpointUrl()).isEqualTo("http://fn-svc:8080");
            assertThat(localService.get("deploy-fn")).hasValueSatisfying(fn ->
                    assertThat(fn.endpointUrl()).isEqualTo("http://fn-svc:8080"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void remove_hidesFunctionWhileTeardownIsInProgress() throws Exception {
        FunctionRegistry localRegistry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        ManagedDeploymentProvider localProvider = provider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService localService = new FunctionService(
                localRegistry,
                defaults,
                ImageValidator.noOp(),
                List.of(listener),
                resolver(localProvider)
        );
        when(localProvider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));

        CountDownLatch removalStarted = new CountDownLatch(1);
        CountDownLatch allowRemoval = new CountDownLatch(1);
        doAnswer(invocation -> {
            removalStarted.countDown();
            if (!allowRemoval.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish remove");
            }
            return null;
        }).when(listener).onRemove("tear-fn");

        FunctionSpec spec = new FunctionSpec(
                "tear-fn", "img:latest",
                null, null, null, null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null
        );
        assertThat(localService.register(spec)).isPresent();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<FunctionSpec>> removeFuture = executor.submit(() -> localService.remove("tear-fn"));

            assertThat(removalStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);

            assertThat(localService.get("tear-fn")).isEmpty();
            assertThat(localService.list()).isEmpty();
            assertThat(localRegistry.get("tear-fn")).isEmpty();

            allowRemoval.countDown();

            assertThat(removeFuture.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            executor.shutdownNow();
        }

        assertThat(localService.get("tear-fn")).isEmpty();
        verify(localProvider).deprovision("tear-fn");
    }

    @Test
    void setReplicas_waitsForRemovalAndDoesNotScaleFunctionUnderTeardown() throws Exception {
        FunctionRegistry localRegistry = new FunctionRegistry();
        FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
        ManagedDeploymentProvider localProvider = provider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService localService = new FunctionService(
                localRegistry,
                defaults,
                ImageValidator.noOp(),
                List.of(listener),
                resolver(localProvider)
        );
        when(localProvider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));

        CountDownLatch removalStarted = new CountDownLatch(1);
        CountDownLatch allowRemoval = new CountDownLatch(1);
        doAnswer(invocation -> {
            removalStarted.countDown();
            if (!allowRemoval.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to finish remove");
            }
            return null;
        }).when(listener).onRemove("tear-fn");

        FunctionSpec spec = new FunctionSpec(
                "tear-fn", "img:latest",
                null, null, null, null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null
        );
        assertThat(localService.register(spec)).isPresent();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<FunctionSpec>> removeFuture = executor.submit(() -> localService.remove("tear-fn"));

            assertThat(removalStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Optional<Integer>> scaleFuture = executor.submit(() -> localService.setReplicas("tear-fn", 2));
            Thread.sleep(150);

            assertThat(scaleFuture.isDone()).isFalse();
            assertThat(localRegistry.get("tear-fn")).isEmpty();

            allowRemoval.countDown();

            assertThat(removeFuture.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(scaleFuture.get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            executor.shutdownNow();
        }

        verify(localProvider, never()).setReplicas("tear-fn", 2);
        verify(localProvider).deprovision("tear-fn");
    }

    private static ManagedDeploymentProvider provider() {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        return provider;
    }

    private static DeploymentProviderResolver resolver(ManagedDeploymentProvider provider) {
        return new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null));
    }
}
