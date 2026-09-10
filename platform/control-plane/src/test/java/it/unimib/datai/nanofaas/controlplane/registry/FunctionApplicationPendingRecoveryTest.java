package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionApplicationPendingRecoveryTest {

    private static final FunctionDefaults DEFAULTS = new FunctionDefaults(1_000, 1, 10, 0);
    private static final List<String> LEFTOVERS = List.of("deployment/fn-r2");

    @TempDir
    Path tempDir;

    @Test
    void identicalPatchAfterListenerFailureReappliesWithoutAnotherCatalogWrite() {
        CountingFailingCatalog catalog = new CountingFailingCatalog(tempDir.resolve("patch-listener.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = provider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = service(registry, provider, listener);
        service.register(spec("fn"));
        clearInvocations(provider, listener);
        doThrow(new IllegalStateException("listener unavailable"))
                .doNothing().when(listener).onRegister(any());
        FunctionUpdateRequest patch = new FunctionUpdateRequest(2, null, null, null);

        assertThatThrownBy(() -> service.update("fn", patch)).hasMessage("listener unavailable");
        assertThat(service.update("fn", patch)).isPresent();
        assertThat(service.update("fn", patch)).isPresent();

        assertThat(catalog.writes()).isEqualTo(2);
        verify(provider, times(2)).updateSpec(any());
        verify(listener, times(2)).onRegister(any());
    }

    @Test
    void identicalPatchAfterProviderFailureReappliesWithoutAnotherCatalogWrite() {
        CountingFailingCatalog catalog = new CountingFailingCatalog(tempDir.resolve("patch-provider.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = provider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = service(registry, provider, listener);
        service.register(spec("fn"));
        clearInvocations(provider, listener);
        doThrow(new IllegalStateException("provider unavailable"))
                .doNothing().when(provider).updateSpec(any());
        FunctionUpdateRequest patch = new FunctionUpdateRequest(2, null, null, null);

        assertThatThrownBy(() -> service.update("fn", patch)).hasMessage("provider unavailable");
        assertThat(service.update("fn", patch)).isPresent();
        assertThat(service.update("fn", patch)).isPresent();

        assertThat(catalog.writes()).isEqualTo(2);
        verify(provider, times(2)).updateSpec(any());
        verify(listener, times(1)).onRegister(any());
    }

    @Test
    void identicalScaleAfterProviderFailureReappliesWithoutAnotherCatalogWrite() {
        CountingFailingCatalog catalog = new CountingFailingCatalog(tempDir.resolve("scale-provider.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        registry.put(managed("fn", 1));
        ManagedDeploymentProvider provider = provider();
        doThrow(new IllegalStateException("provider unavailable"))
                .doNothing().when(provider).setReplicas("fn", 3);
        ManagedDeploymentCoordinator coordinator = coordinator(registry, provider, new FunctionOperationLocks());
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("fn", "k8s");

        assertThatThrownBy(() -> coordinator.setReplicas(target, 3)).hasMessage("provider unavailable");
        assertThat(coordinator.setReplicas(target, 3)).isTrue();
        assertThat(coordinator.setReplicas(target, 3)).isTrue();

        assertThat(catalog.writes()).isEqualTo(2);
        verify(provider, times(2)).setReplicas("fn", 3);
    }

    @Test
    void reconcileFailureAfterDeleteSaveFailureStaysUnavailableUntilDeleteRetrySucceeds() {
        CountingFailingCatalog catalog = new CountingFailingCatalog(tempDir.resolve("triple-retry.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = provider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = service(registry, provider, listener);
        service.register(spec("fn"));
        clearInvocations(provider, listener);
        catalog.failSaves(true);
        doNothing().when(provider).deprovision("fn");
        when(provider.reconcile(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("reconcile unavailable"));

        assertThatThrownBy(() -> service.remove("fn"))
                .hasMessage("catalog failure")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .anySatisfy(suppressed -> assertThat(suppressed).hasMessage("reconcile unavailable")));

        assertUnavailableFromService(service, "fn");
        assertThat(new FunctionRegistry(catalog).getRegistered("fn")).isPresent();
        verify(listener, times(1)).onRemove("fn");
        verify(listener, never()).onRegister(any());

        catalog.failSaves(false);
        assertThat(service.remove("fn")).map(FunctionSpec::name).contains("fn");

        assertThat(service.get("fn")).isEmpty();
        assertThat(new FunctionRegistry(catalog).getRegistered("fn")).isEmpty();
        verify(listener, times(1)).onRemove("fn");
    }

    @Test
    void restartKeepsFailedReconcileUnavailableAndPublishesOnlyAfterAHealthyRestart() {
        CountingFailingCatalog catalog = new CountingFailingCatalog(tempDir.resolve("triple-restart.json"));
        FunctionRegistry originalRegistry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = provider();
        FunctionRegistrationListener originalListener = mock(FunctionRegistrationListener.class);
        FunctionService originalService = service(originalRegistry, provider, originalListener);
        originalService.register(spec("fn"));
        catalog.failSaves(true);
        doNothing().when(provider).deprovision("fn");
        when(provider.reconcile(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("reconcile unavailable"));
        assertThatThrownBy(() -> originalService.remove("fn")).hasMessage("catalog failure");
        catalog.failSaves(false);

        FunctionRegistry failedRestartRegistry = new FunctionRegistry(catalog);
        FunctionRegistrationListener failedRestartListener = mock(FunctionRegistrationListener.class);
        new FunctionCatalogRestorer(failedRestartRegistry, resolver(provider), List.of(failedRestartListener))
                .run(new DefaultApplicationArguments());
        FunctionService failedRestartService = service(failedRestartRegistry, provider, failedRestartListener);

        assertUnavailableFromService(failedRestartService, "fn");
        verify(failedRestartListener, never()).onRegister(any());

        FunctionRegistry healthyRestartRegistry = new FunctionRegistry(catalog);
        FunctionRegistrationListener healthyRestartListener = mock(FunctionRegistrationListener.class);
        doReturn(new ProvisionResult("http://fn-restored/invoke", "k8s"))
                .when(provider).reconcile(any(), anyInt(), anyMap());
        new FunctionCatalogRestorer(healthyRestartRegistry, resolver(provider), List.of(healthyRestartListener))
                .run(new DefaultApplicationArguments());
        FunctionService healthyRestartService = service(healthyRestartRegistry, provider, healthyRestartListener);

        assertThat(healthyRestartService.get("fn")).isPresent();
        assertThat(healthyRestartService.list()).extracting(FunctionSpec::name).containsExactly("fn");
        verify(healthyRestartListener, times(1)).onRegister(any());
    }

    @Test
    void partialDeprovisionThatRacesManualScaleReturnsConflictWithoutProviderScale() throws Exception {
        FunctionRegistry registry = new FunctionRegistry();
        ManagedDeploymentProvider provider = provider();
        FunctionOperationLocks locks = new FunctionOperationLocks();
        FunctionService service = new FunctionService(
                registry, DEFAULTS, ImageValidator.noOp(), List.of(), resolver(provider), locks,
                coordinator(registry, provider, locks));
        service.register(spec("fn"));
        CountDownLatch insideDeprovision = new CountDownLatch(1);
        CountDownLatch scaleStarted = new CountDownLatch(1);
        CountDownLatch releaseDeprovision = new CountDownLatch(1);
        doAnswer(invocation -> {
            insideDeprovision.countDown();
            assertThat(releaseDeprovision.await(5, TimeUnit.SECONDS)).isTrue();
            throw new PartialDeprovisionException("fn", "k8s", LEFTOVERS,
                    List.of(new IllegalStateException("engine unavailable")));
        }).when(provider).deprovision("fn");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> removal = executor.submit(() -> thrownBy(() -> service.remove("fn")));
            assertThat(insideDeprovision.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> scale = executor.submit(() -> {
                scaleStarted.countDown();
                return thrownBy(() -> service.setReplicas("fn", 3));
            });
            assertThat(scaleStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseDeprovision.countDown();

            assertThat(removal.get(5, TimeUnit.SECONDS)).isInstanceOf(FunctionRemovalPendingException.class);
            assertThat(scale.get(5, TimeUnit.SECONDS)).isInstanceOf(FunctionRemovalPendingException.class);
        }

        verify(provider, never()).setReplicas("fn", 3);
    }

    @Test
    void pendingMarkersPlateauAtOneEntryPerDurableFunctionAndReturnToZero() {
        FunctionRegistry registry = new FunctionRegistry();
        List<RegisteredFunction> functions = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            functions.add(managed("fn-" + index, 1));
        }
        registry.replaceAllDurably(functions);
        FunctionApplicationState state = registry.applicationState();
        com.sun.management.ThreadMXBean allocations =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        long before = allocations.getThreadAllocatedBytes(threadId);

        for (RegisteredFunction function : functions) {
            state.markUpdate(function);
            state.markScale(function.managedDeploymentTarget().orElseThrow(), 2);
        }

        long allocatedBytes = allocations.getThreadAllocatedBytes(threadId) - before;
        assertThat(state.retainedFunctionCount()).isEqualTo(1_000);
        assertThat(state.retainedMarkerCount()).isEqualTo(2_000);
        assertThat(allocatedBytes).isPositive();
        System.out.printf("P15_PENDING_RETENTION functions=%d markers=%d allocatedBytes=%d%n",
                state.retainedFunctionCount(), state.retainedMarkerCount(), allocatedBytes);

        for (RegisteredFunction function : functions) {
            state.completeUpdate(function);
            state.completeScale(function.managedDeploymentTarget().orElseThrow(), 2);
        }

        assertThat(state.retainedFunctionCount()).isZero();
        assertThat(state.retainedMarkerCount()).isZero();

        RegisteredFunction retained = managed("retained", 1);
        registry.put(retained);
        state.markUpdate(retained);
        state.markScale(retained.managedDeploymentTarget().orElseThrow(), 2);
        assertThat(state.retainedFunctionCount()).isEqualTo(1);

        registry.removeRegistered("retained");

        assertThat(state.retainedFunctionCount()).isZero();
        assertThat(state.retainedMarkerCount()).isZero();
    }

    @Test
    void restoreAndReloadDiscardOldVolatileApplicationMarkers() {
        CountingFailingCatalog catalog = new CountingFailingCatalog(tempDir.resolve("marker-reload.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        RegisteredFunction function = managed("fn", 1);
        registry.put(function);
        registry.applicationState().markUpdate(function);
        registry.applicationState().markScale(function.managedDeploymentTarget().orElseThrow(), 2);
        ManagedDeploymentProvider provider = provider();
        when(provider.reconcile(any(), anyInt(), anyMap()))
                .thenReturn(new ProvisionResult("http://fn-restored/invoke", "k8s"));

        FunctionRegistry reloaded = new FunctionRegistry(catalog);
        assertThat(reloaded.applicationState().retainedFunctionCount()).isZero();

        new FunctionCatalogRestorer(registry, resolver(provider), List.of())
                .run(new DefaultApplicationArguments());

        assertThat(registry.applicationState().retainedFunctionCount()).isZero();
        assertThat(registry.applicationState().retainedMarkerCount()).isZero();
    }

    private static void assertUnavailableFromService(FunctionService service, String name) {
        assertThatThrownBy(() -> service.get(name)).hasMessageContaining("pending");
        assertThat(service.getRegistered(name)).isEmpty();
        assertThat(service.list()).isEmpty();
        assertThat(service.listRegistered()).isEmpty();
    }

    private static Throwable thrownBy(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static FunctionService service(FunctionRegistry registry,
                                           ManagedDeploymentProvider provider,
                                           FunctionRegistrationListener... listeners) {
        return new FunctionService(registry, DEFAULTS, ImageValidator.noOp(), List.of(listeners), resolver(provider));
    }

    private static ManagedDeploymentCoordinator coordinator(FunctionRegistry registry,
                                                             ManagedDeploymentProvider provider,
                                                             FunctionOperationLocks locks) {
        return new ManagedDeploymentCoordinator(resolver(provider), registry, locks);
    }

    private static DeploymentProviderResolver resolver(ManagedDeploymentProvider provider) {
        return new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null));
    }

    private static ManagedDeploymentProvider provider() {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn/invoke", "k8s",
                ExecutionMode.DEPLOYMENT, null, Map.of("deployment", "fn")));
        return provider;
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "example:latest", List.of(), Map.of(), null,
                1_000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, null, null, null, null);
    }

    private static RegisteredFunction managed(String name, int replicas) {
        return new RegisteredFunction(spec(name), new DeploymentMetadata(
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null,
                "http://" + name + "/invoke", Map.of("deployment", name), replicas));
    }

    private static final class CountingFailingCatalog extends FunctionCatalog {
        private final AtomicInteger writes = new AtomicInteger();
        private volatile boolean failSaves;

        private CountingFailingCatalog(Path path) {
            super(new FunctionCatalogProperties(path), new ObjectMapper(),
                    Validation.buildDefaultValidatorFactory().getValidator());
        }

        @Override
        public void save(Collection<RegisteredFunction> functions) {
            if (failSaves) {
                throw new IllegalStateException("catalog failure");
            }
            super.save(functions);
            writes.incrementAndGet();
        }

        private int writes() {
            return writes.get();
        }

        private void failSaves(boolean fail) {
            failSaves = fail;
        }
    }
}
