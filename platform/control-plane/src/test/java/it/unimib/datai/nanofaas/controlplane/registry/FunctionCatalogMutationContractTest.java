package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionCatalogMutationContractTest {

    @TempDir
    Path tempDir;

    @Test
    void puttingAnEqualRecordDoesNotRewriteTheCatalog() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        RegisteredFunction function = managedFunction("fn", 1);

        registry.put(function);
        registry.put(function);

        assertThat(catalog.writes()).isEqualTo(1);
        assertThat(new FunctionRegistry(catalog).listRegisteredForRecovery()).contains(function);
    }

    @Test
    void settingTheCurrentDesiredReplicaCountIsACompleteNoOp() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        RegisteredFunction function = managedFunction("fn", 3);
        registry.put(function);
        ManagedDeploymentProvider provider = provider();
        ManagedDeploymentCoordinator coordinator = coordinator(registry, provider);

        assertThat(coordinator.setReplicas(new ManagedDeploymentTarget("fn", "k8s"), 3)).isTrue();

        assertThat(catalog.writes()).isEqualTo(1);
        verify(provider, never()).setReplicas("fn", 3);
        assertThat(new FunctionRegistry(catalog).listRegisteredForRecovery()).contains(function);
    }

    @Test
    void patchingOnlyCurrentValuesDoesNotWriteOrNotifyDependents() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = provider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = service(registry, provider, List.of(listener));
        RegisteredFunction function = service.register(spec("fn", 1_000, ExecutionMode.DEPLOYMENT)).orElseThrow();
        clearInvocations(provider, listener);

        assertThat(service.update("fn", new FunctionUpdateRequest(1, 1_000, 0, null)))
                .contains(function);

        assertThat(catalog.writes()).isEqualTo(1);
        verify(provider, never()).updateSpec(function.spec());
        verify(listener, never()).onRegister(function.spec());
    }

    @Test
    void providerFailureKeepsThePersistedDesiredTargetForRestartReconciliation() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        registry.put(managedFunction("fn", 1));
        ManagedDeploymentProvider provider = provider();
        doThrow(new IllegalStateException("provider unavailable")).when(provider).setReplicas("fn", 3);
        ManagedDeploymentCoordinator coordinator = coordinator(registry, provider);

        assertThatThrownBy(() -> coordinator.setReplicas(new ManagedDeploymentTarget("fn", "k8s"), 3))
                .hasMessage("provider unavailable");

        assertThat(registry.getRegistered("fn")).get()
                .extracting(RegisteredFunction::desiredReplicas)
                .isEqualTo(3);
        assertThat(recoveredFunction(catalog, "fn"))
                .extracting(RegisteredFunction::desiredReplicas)
                .isEqualTo(3);
        assertThat(catalog.writes()).isEqualTo(2);
    }

    @Test
    void scalePersistenceFailureDoesNotConfirmOrCallTheProvider() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        RegisteredFunction function = managedFunction("fn", 1);
        registry.put(function);
        ManagedDeploymentProvider provider = provider();
        ManagedDeploymentCoordinator coordinator = coordinator(registry, provider);
        catalog.failNextSave();

        assertThatThrownBy(() -> coordinator.setReplicas(new ManagedDeploymentTarget("fn", "k8s"), 3))
                .hasMessage("catalog failure");

        verify(provider, never()).setReplicas("fn", 3);
        assertThat(registry.getRegistered("fn")).contains(function);
        assertThat(new FunctionRegistry(catalog).listRegisteredForRecovery()).contains(function);
        assertThat(catalog.writes()).isEqualTo(1);
    }

    @Test
    void providerFailureInvalidatesTheVolatileObservation() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("fn", 1));
        ManagedDeploymentProvider provider = provider();
        doThrow(new IllegalStateException("provider unavailable")).when(provider).setReplicas("fn", 3);
        ReplicaStatusSnapshot observations = mock(ReplicaStatusSnapshot.class);
        ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                registry,
                new FunctionOperationLocks(),
                observations);

        assertThatThrownBy(() -> coordinator.setReplicas(new ManagedDeploymentTarget("fn", "k8s"), 3))
                .hasMessage("provider unavailable");

        verify(observations).invalidate("fn");
    }

    @Test
    void patchProviderFailureIsReportedAfterTheNewDesiredSpecIsDurable() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        registry.put(managedFunction("fn", 1));
        ManagedDeploymentProvider provider = provider();
        doThrow(new IllegalStateException("provider update failed")).when(provider)
                .updateSpec(org.mockito.ArgumentMatchers.any());
        FunctionService service = service(registry, provider, List.of());

        assertThatThrownBy(() -> service.update("fn", new FunctionUpdateRequest(2, null, null, null)))
                .hasMessage("provider update failed");

        assertThat(recoveredFunction(catalog, "fn").spec())
                .extracting(FunctionSpec::concurrency)
                .isEqualTo(2);
        assertThat(catalog.writes()).isEqualTo(2);
    }

    @Test
    void removeProviderFailureLeavesTheExistingSnapshotWithoutRewritingIt() {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        RegisteredFunction function = managedFunction("fn", 1);
        registry.put(function);
        ManagedDeploymentProvider provider = provider();
        doThrow(new IllegalStateException("provider remove failed")).when(provider).deprovision("fn");
        FunctionService service = service(registry, provider, List.of());

        assertThatThrownBy(() -> service.remove("fn"))
                .hasMessage("provider remove failed");

        assertThat(registry.getRegistered("fn")).contains(function);
        assertThat(new FunctionRegistry(catalog).listRegisteredForRecovery()).contains(function);
        assertThat(catalog.writes()).isEqualTo(1);
    }

    @Test
    void concurrentDurableUpdatesAreBothPresentAfterReload() throws Exception {
        CountingCatalog catalog = new CountingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        CyclicBarrier start = new CyclicBarrier(3);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> putAfterBarrier(registry, start, "first"));
            Future<?> second = executor.submit(() -> putAfterBarrier(registry, start, "second"));
            start.await(5, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertThat(new FunctionRegistry(catalog).list().stream().map(FunctionSpec::name))
                .containsExactlyInAnyOrder("first", "second");
        assertThat(catalog.writes()).isEqualTo(2);
    }

    private static void putAfterBarrier(FunctionRegistry registry, CyclicBarrier start, String name) {
        try {
            start.await(5, TimeUnit.SECONDS);
            registry.put(RegisteredFunction.nonManaged(spec(name, 1_000, ExecutionMode.LOCAL)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RegisteredFunction recoveredFunction(CountingCatalog catalog, String name) {
        return new FunctionRegistry(catalog).listRegisteredForRecovery().stream()
                .filter(function -> function.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ManagedDeploymentCoordinator coordinator(FunctionRegistry registry,
                                                             ManagedDeploymentProvider provider) {
        return new ManagedDeploymentCoordinator(
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                registry,
                new FunctionOperationLocks());
    }

    private static FunctionService service(FunctionRegistry registry,
                                           ManagedDeploymentProvider provider,
                                           List<FunctionRegistrationListener> listeners) {
        return new FunctionService(
                registry,
                new FunctionDefaults(1_000, 1, 1, 0),
                ImageValidator.noOp(),
                listeners,
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)));
    }

    private static ManagedDeploymentProvider provider() {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(provider.provision(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ProvisionResult("http://fn", "k8s"));
        return provider;
    }

    private static RegisteredFunction managedFunction(String name, int desiredReplicas) {
        FunctionSpec spec = spec(name, 1_000, ExecutionMode.DEPLOYMENT);
        DeploymentMetadata metadata = new DeploymentMetadata(
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null,
                "http://" + name, Map.of("deployment", name), desiredReplicas);
        return new RegisteredFunction(spec, metadata);
    }

    private static FunctionSpec spec(String name, int timeoutMs, ExecutionMode mode) {
        return new FunctionSpec(name, "example:latest", List.of(), Map.of(), null,
                timeoutMs, 1, 1, 0, null, mode, null, null, null, null);
    }

    static final class CountingCatalog extends FunctionCatalog {
        private int writes;
        private boolean failNextSave;

        CountingCatalog(Path path) {
            super(new FunctionCatalogProperties(path), new ObjectMapper(),
                    Validation.buildDefaultValidatorFactory().getValidator());
        }

        @Override
        public void save(Collection<RegisteredFunction> functions) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("catalog failure");
            }
            super.save(functions);
            writes++;
        }

        void failNextSave() {
            failNextSave = true;
        }

        int writes() {
            return writes;
        }
    }
}
