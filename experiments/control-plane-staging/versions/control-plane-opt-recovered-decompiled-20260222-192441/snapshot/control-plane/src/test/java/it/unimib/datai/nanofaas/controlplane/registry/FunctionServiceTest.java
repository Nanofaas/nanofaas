package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FunctionServiceTest {

    private FunctionRegistry registry;
    private FunctionDefaults defaults;
    private KubernetesResourceManager resourceManager;
    private FunctionRegistrationListener listener;
    private ImageValidator imageValidator;
    private FunctionService service;

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry();
        defaults = new FunctionDefaults(30000, 4, 100, 3);
        resourceManager = mock(KubernetesResourceManager.class);
        listener = mock(FunctionRegistrationListener.class);
        imageValidator = mock(ImageValidator.class);
        service = new FunctionService(registry, defaults, resourceManager, imageValidator, List.of(listener));
    }

    @Test
    void register_deploymentMode_provisionsAndSetsUrl() {
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        Optional<FunctionSpec> result = service.register(spec);

        assertTrue(result.isPresent());
        assertEquals("http://fn-svc:8080", result.get().endpointUrl());
        verify(imageValidator).validate(resolved("fn", "img:latest", ExecutionMode.DEPLOYMENT));
        verify(resourceManager).provision(any());
        verify(listener).onRegister(any());
    }

    @Test
    void register_duplicate_returnsEmptyAndDoesNotDeprovision() {
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        service.register(spec);
        Optional<FunctionSpec> dup = service.register(spec);

        assertTrue(dup.isEmpty());
        verify(resourceManager, times(1)).provision(any());
        verify(resourceManager, never()).deprovision("fn");
        verify(listener, times(1)).onRegister(any());
    }

    @Test
    void register_localMode_noProvisioning() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, null);

        Optional<FunctionSpec> result = service.register(spec);

        assertTrue(result.isPresent());
        verify(imageValidator).validate(resolved("fn", "img:latest", ExecutionMode.LOCAL));
        verify(resourceManager, never()).provision(any());
    }

    @Test
    void register_duplicateSkipsImageValidationOnConflict() {
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        service.register(spec);
        service.register(spec);

        verify(imageValidator, times(1)).validate(resolved("fn", "img:latest", ExecutionMode.DEPLOYMENT));
    }

    @Test
    void perFunctionLocksAreCleanedUpAfterOperations() throws Exception {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, null);

        service.register(spec);
        service.remove("fn");
        service.remove("ghost");

        assertEquals(0, functionLockCount(service));
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
                it.unimib.datai.nanofaas.common.model.RuntimeMode.HTTP,
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
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        service.register(spec);

        Optional<FunctionSpec> removed = service.remove("fn");

        assertTrue(removed.isPresent());
        verify(resourceManager).deprovision("fn");
        verify(listener).onRemove("fn");
    }

    @Test
    void remove_nonexistent_returnsEmpty() {
        assertTrue(service.remove("ghost").isEmpty());
    }

    @Test
    void setReplicas_success() {
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        service.register(spec);

        Optional<Integer> result = service.setReplicas("fn", 3);

        assertTrue(result.isPresent());
        assertEquals(3, result.get());
        verify(resourceManager).setReplicas("fn", 3);
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

    private static int functionLockCount(FunctionService service) throws Exception {
        Field functionLocksField = FunctionService.class.getDeclaredField("functionLocks");
        functionLocksField.setAccessible(true);
        ConcurrentHashMap<?, ?> functionLocks = (ConcurrentHashMap<?, ?>) functionLocksField.get(service);
        return functionLocks.size();
    }
}
