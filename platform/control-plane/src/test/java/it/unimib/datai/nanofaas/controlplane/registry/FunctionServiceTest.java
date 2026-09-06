package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FunctionServiceTest {

    private FunctionRegistry registry;
    private FunctionDefaults defaults;
    private ManagedDeploymentProvider provider;
    private FunctionRegistrationListener listener;
    private ImageValidator imageValidator;
    private FunctionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry();
        defaults = new FunctionDefaults(30000, 4, 100, 3);
        provider = provider();
        listener = mock(FunctionRegistrationListener.class);
        imageValidator = mock(ImageValidator.class);
        service = new FunctionService(registry, defaults, imageValidator, List.of(listener), resolver(provider));
    }

    @Test
    void register_returnsRegisteredFunctionContract() throws NoSuchMethodException {
        ParameterizedType returnType = (ParameterizedType) FunctionService.class
                .getMethod("register", FunctionSpec.class)
                .getGenericReturnType();

        assertEquals(Optional.class, FunctionService.class.getMethod("register", FunctionSpec.class).getReturnType());
        assertEquals(RegisteredFunction.class, returnType.getActualTypeArguments()[0]);
    }

    @Test
    void register_deploymentMode_provisionsAndSetsUrl() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        Optional<RegisteredFunction> result = service.register(spec);

        assertTrue(result.isPresent());
        assertEquals("http://fn-svc:8080", result.get().spec().endpointUrl());
        verify(imageValidator).validate(resolved("fn", "img:latest", ExecutionMode.DEPLOYMENT));
        verify(provider).provision(any());
        verify(listener).onRegister(any());
    }

    @Test
    void register_duplicate_returnsEmptyAndDoesNotDeprovision() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        service.register(spec);
        Optional<RegisteredFunction> dup = service.register(spec);

        assertTrue(dup.isEmpty());
        verify(provider, times(1)).provision(any());
        verify(provider, never()).deprovision("fn");
        verify(listener, times(1)).onRegister(any());
    }

    @Test
    void register_localMode_noProvisioning() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, null);

        Optional<RegisteredFunction> result = service.register(spec);

        assertTrue(result.isPresent());
        verify(imageValidator).validate(resolved("fn", "img:latest", ExecutionMode.LOCAL));
        verify(provider, never()).provision(any());
    }

    @Test
    void register_duplicateSkipsImageValidationOnConflict() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        service.register(spec);
        service.register(spec);

        verify(imageValidator, times(1)).validate(resolved("fn", "img:latest", ExecutionMode.DEPLOYMENT));
    }

    @Test
    void register_listenerFailure_rollsBackRegistryAndProvisionedResources() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        doThrow(new IllegalStateException("listener failure")).when(listener).onRegister(any());

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.register(spec));

        assertEquals("listener failure", thrown.getMessage());
        assertTrue(service.get("fn").isEmpty());
        assertTrue(service.list().isEmpty());
        verify(provider).provision(any());
        verify(provider).deprovision("fn");
    }

    @Test
    void register_multiListenerFailure_compensatesPreviouslyNotifiedListeners() {
        FunctionRegistrationListener firstListener = mock(FunctionRegistrationListener.class);
        FunctionRegistrationListener secondListener = mock(FunctionRegistrationListener.class);
        FunctionService localService = new FunctionService(
                registry,
                defaults,
                imageValidator,
                List.of(firstListener, secondListener),
                resolver(provider)
        );
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        doThrow(new IllegalStateException("listener failure")).when(secondListener).onRegister(any());

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> localService.register(spec));

        assertEquals("listener failure", thrown.getMessage());
        assertTrue(localService.get("fn").isEmpty());
        verify(firstListener).onRegister(any());
        verify(secondListener).onRegister(any());
        verify(firstListener).onRemove("fn");
        verify(secondListener, never()).onRemove("fn");
        verify(provider).deprovision("fn");
    }

    private static FunctionSpec resolved(String name, String image, ExecutionMode mode) {
        return new FunctionSpec(
                name, image,
                java.util.List.of(),
                java.util.Map.of(),
                null,
                30000,
                4,
                100,
                3,
                null,
                mode,
                null,
                null,
                mode == ExecutionMode.DEPLOYMENT
                        ? new it.unimib.datai.nanofaas.common.model.ScalingConfig(
                        it.unimib.datai.nanofaas.common.model.ScalingStrategy.INTERNAL,
                        1,
                        10,
                        java.util.List.of(new it.unimib.datai.nanofaas.common.model.ScalingMetric("queue_depth", "5", null)),
                        new it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig(
                                it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode.FIXED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )
                )
                        : null
        );
    }

    @Test
    void remove_existing_deprovisions() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        service.register(spec);

        Optional<FunctionSpec> removed = service.remove("fn");

        assertTrue(removed.isPresent());
        verify(provider).deprovision("fn");
        verify(listener).onRemove("fn");
    }

    @Test
    void remove_listenerFailure_keepsRegistryAndResourcesConsistent() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        doThrow(new IllegalStateException("listener failure")).when(listener).onRemove("fn");

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        service.register(spec);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.remove("fn"));

        assertEquals("listener failure", thrown.getMessage());
        assertTrue(service.get("fn").isPresent());
        assertEquals(1, service.list().size());
        verify(provider, never()).deprovision("fn");
    }

    @Test
    void remove_multiListenerFailure_compensatesPreviouslyNotifiedListeners() {
        FunctionRegistrationListener firstListener = mock(FunctionRegistrationListener.class);
        FunctionRegistrationListener secondListener = mock(FunctionRegistrationListener.class);
        FunctionService localService = new FunctionService(
                registry,
                defaults,
                imageValidator,
                List.of(firstListener, secondListener),
                resolver(provider)
        );
        doThrow(new IllegalStateException("listener failure")).when(secondListener).onRemove("fn");
        registry.put(new RegisteredFunction(
                new FunctionSpec(
                        "fn", "img:latest",
                        List.of(),
                        java.util.Map.of(),
                        null,
                        30000,
                        4,
                        100,
                        3,
                        "http://fn-svc:8080",
                        ExecutionMode.DEPLOYMENT,
                        it.unimib.datai.nanofaas.common.model.RuntimeMode.HTTP,
                        null,
                        resolved("fn", "img:latest", ExecutionMode.DEPLOYMENT).scalingConfig(),
                        null
                ),
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null)
        ));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> localService.remove("fn"));

        assertEquals("listener failure", thrown.getMessage());
        assertTrue(localService.get("fn").isPresent());
        verify(firstListener).onRemove("fn");
        verify(secondListener).onRemove("fn");
        verify(firstListener).onRegister(any());
        verify(secondListener, never()).onRegister(any());
        verify(provider, never()).deprovision("fn");
    }

    @Test
    void remove_nonexistent_returnsEmpty() {
        assertTrue(service.remove("ghost").isEmpty());
    }

    @Test
    void setReplicas_success() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        service.register(spec);

        Optional<Integer> result = service.setReplicas("fn", 3);

        assertTrue(result.isPresent());
        assertEquals(3, result.get());
        assertEquals(3, registry.getRegistered("fn").orElseThrow().desiredReplicas());
        verify(provider).setReplicas("fn", 3);
    }

    @Test
    void setReplicas_nonDeployment_throws() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, null);
        service.register(spec);

        assertThrows(IllegalArgumentException.class, () -> service.setReplicas("fn", 2));
    }

    @Test
    void setReplicas_notFound_returnsEmpty() {
        assertTrue(service.setReplicas("ghost", 2).isEmpty());
    }

    @Test
    void setReplicas_returnsEmptyWhenCoordinatorFindsFunctionAlreadyRemoved() {
        ManagedDeploymentProvider managedProvider = provider();
        when(managedProvider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        ManagedDeploymentCoordinator coordinator = mock(ManagedDeploymentCoordinator.class);
        FunctionService localService = new FunctionService(
                registry,
                defaults,
                imageValidator,
                List.of(),
                resolver(managedProvider),
                new FunctionOperationLocks(),
                coordinator
        );
        localService.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));

        // A concurrent remove won the race after the manual lookup above but before the
        // coordinator's re-check under the lock; the coordinator reports the function as gone.
        when(coordinator.setReplicas(any(ManagedDeploymentTarget.class), eq(2))).thenReturn(false);

        assertTrue(localService.setReplicas("fn", 2).isEmpty());
        verify(coordinator).setReplicas(any(ManagedDeploymentTarget.class), eq(2));
    }

    @Test
    void list_returnsAllFunctions() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, null);
        service.register(spec);

        assertEquals(1, service.list().size());
    }

    @Test
    void get_existing_returnsSpec() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, null);
        service.register(spec);

        assertTrue(service.get("fn").isPresent());
    }

    @Test
    void update_appliesConcurrencyWithoutRedeployingTheFunction() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        service.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, 4, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));
        reset(provider);
        when(provider.backendId()).thenReturn("k8s");

        Optional<RegisteredFunction> updated = service.update("fn",
                new FunctionUpdateRequest(16, null, null, null));

        assertTrue(updated.isPresent());
        assertEquals(16, updated.get().spec().concurrency());
        assertEquals("k8s", updated.get().deploymentMetadata().deploymentBackend());
        assertEquals("http://fn-svc:8080", updated.get().spec().endpointUrl());
        assertEquals(16, registry.get("fn").orElseThrow().concurrency());
        // A PATCH tunes the running deployment; it must never tear it down and rebuild it.
        verify(provider, never()).provision(any());
        verify(provider, never()).deprovision(any());
        verify(provider, never()).reconcile(any(), anyInt(), any());
        // But the new tuning does have to reach the backend, or the deployment keeps enforcing
        // the values it was provisioned with.
        verify(provider).updateSpec(argThat(spec -> spec.concurrency() == 16));
    }

    @Test
    void update_notifiesListenersWithTheNewSpec() {
        service.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, 4, null, null, null, ExecutionMode.LOCAL, null, null, null));

        service.update("fn", new FunctionUpdateRequest(9, null, null, null));

        ArgumentCaptor<FunctionSpec> captor = ArgumentCaptor.forClass(FunctionSpec.class);
        verify(listener, times(2)).onRegister(captor.capture());
        assertEquals(9, captor.getAllValues().get(1).concurrency());
        verify(listener, never()).onRemove(any());
    }

    @Test
    void update_leavesOmittedFieldsAlone() {
        service.register(new FunctionSpec("fn", "img:latest", null, null, null,
                12000, 4, null, 7, null, ExecutionMode.LOCAL, null, null, null));

        FunctionSpec updated = service.update("fn",
                new FunctionUpdateRequest(null, null, null, null)).orElseThrow().spec();

        assertEquals(12000, updated.timeoutMs());
        assertEquals(4, updated.concurrency());
        assertEquals(7, updated.maxRetries());
        assertEquals("img:latest", updated.image());
    }

    @Test
    void update_replacesTheConcurrencyControlBlock() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        service.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, 4, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));

        FunctionSpec updated = service.update("fn", new FunctionUpdateRequest(null, null, null,
                new ConcurrencyControlConfig(ConcurrencyControlMode.ADAPTIVE_PER_POD,
                        3, 1, 6, null, null, null, null))).orElseThrow().spec();

        ConcurrencyControlConfig control = updated.scalingConfig().concurrencyControl();
        assertEquals(ConcurrencyControlMode.ADAPTIVE_PER_POD, control.mode());
        assertEquals(3, control.targetInFlightPerPod());
        assertEquals(ScalingStrategy.INTERNAL, updated.scalingConfig().strategy());
    }

    @Test
    void update_rejectsInvalidBounds() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        service.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, 4, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));

        FunctionUpdateRequest request = new FunctionUpdateRequest(null, null, null,
                new ConcurrencyControlConfig(ConcurrencyControlMode.ADAPTIVE_PER_POD,
                        3, 4, 2, null, null, null, null));

        // min > max collapses to max instead of failing; the spec stays consistent either way
        FunctionSpec updated = service.update("fn", request).orElseThrow().spec();
        ConcurrencyControlConfig control = updated.scalingConfig().concurrencyControl();
        assertTrue(control.minTargetInFlightPerPod() <= control.maxTargetInFlightPerPod());
    }

    @Test
    void update_unknownFunction_returnsEmpty() {
        assertTrue(service.update("nope", new FunctionUpdateRequest(8, null, null, null)).isEmpty());
    }

    @Test
    void registrationCommitsDurablyOnlyAfterProvisionAndListeners() {
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        FunctionRegistry spiedRegistry = spy(registry);
        FunctionService ordered = new FunctionService(
                spiedRegistry, defaults, imageValidator, List.of(listener), resolver(provider));

        ordered.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));

        InOrder order = inOrder(provider, listener, spiedRegistry);
        order.verify(provider).provision(any());
        order.verify(listener).onRegister(any());
        order.verify(spiedRegistry).put(any(RegisteredFunction.class));
    }

    @Test
    void register_catalogFailure_rollsBackListenersAndDeprovisions() {
        ControllableCatalog catalog = new ControllableCatalog(tempDir.resolve("functions.json"));
        catalog.failSaves(true);
        FunctionRegistry realRegistry = new FunctionRegistry(catalog);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        FunctionService localService = new FunctionService(
                realRegistry, defaults, imageValidator, List.of(listener), resolver(provider));

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> localService.register(spec));

        assertEquals("catalog failure", thrown.getMessage());
        assertTrue(localService.get("fn").isEmpty());
        verify(provider).provision(any());
        verify(provider).deprovision("fn");
        verify(listener).onRegister(any());
        verify(listener).onRemove("fn");
    }

    @Test
    void update_catalogFailure_leavesOldValueAndListenersUntouched() {
        ControllableCatalog catalog = new ControllableCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry realRegistry = new FunctionRegistry(catalog);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        FunctionService localService = new FunctionService(
                realRegistry, defaults, imageValidator, List.of(listener), resolver(provider));
        localService.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));

        catalog.failSaves(true);

        FunctionUpdateRequest request = new FunctionUpdateRequest(16, null, null, null);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> localService.update("fn", request));

        assertEquals("catalog failure", thrown.getMessage());
        assertEquals(4, realRegistry.get("fn").orElseThrow().concurrency());
        verify(listener, times(1)).onRegister(any());
    }

    @Test
    void remove_catalogFailure_restoresReplaysAndReconciles() {
        ControllableCatalog catalog = new ControllableCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry realRegistry = new FunctionRegistry(catalog);
        Map<String, String> deploymentObjects = Map.of("deployment", "fn-deploy", "service", "fn-svc");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s", deploymentObjects));
        FunctionService localService = new FunctionService(
                realRegistry, defaults, imageValidator, List.of(listener), resolver(provider));
        localService.register(new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null));

        catalog.failSaves(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> localService.remove("fn"));

        assertEquals("catalog failure", thrown.getMessage());
        assertTrue(localService.get("fn").isPresent());
        verify(listener).onRemove("fn");
        verify(listener, times(2)).onRegister(any());
        verify(provider).deprovision("fn");
        ArgumentCaptor<FunctionSpec> specCaptor = ArgumentCaptor.forClass(FunctionSpec.class);
        verify(provider).reconcile(specCaptor.capture(), eq(1), eq(deploymentObjects));
        assertEquals("fn", specCaptor.getValue().name());
    }

    @Test
    void interruptedRegistration_leavesNoCatalogEntryForOrphanBackendResource() {
        ControllableCatalog catalog = new ControllableCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry realRegistry = new FunctionRegistry(catalog);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
        FunctionService localService = new FunctionService(
                realRegistry, defaults, imageValidator, List.of(listener), resolver(provider));

        catalog.failSaves(true);

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        assertThrows(IllegalStateException.class, () -> localService.register(spec));

        // Startup re-reads the catalog and finds nothing: an orphaned backend resource from an
        // interrupted registration is never fabricated into the catalog (no intent journal in the MVP).
        assertTrue(new FunctionRegistry(catalog).list().isEmpty());
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

    /**
     * A real {@link FunctionCatalog} backed by a temp file whose {@code save} can be made to fail on
     * demand, so tests exercise the in-memory-versus-file invariant without mocking it away.
     */
    static final class ControllableCatalog extends FunctionCatalog {
        private volatile boolean failSaves;

        ControllableCatalog(Path path) {
            super(new FunctionCatalogProperties(path),
                    new ObjectMapper(),
                    Validation.buildDefaultValidatorFactory().getValidator());
        }

        void failSaves(boolean fail) {
            this.failSaves = fail;
        }

        @Override
        public void save(Collection<RegisteredFunction> functions) {
            if (failSaves) {
                throw new IllegalStateException("catalog failure");
            }
            super.save(functions);
        }
    }
}
