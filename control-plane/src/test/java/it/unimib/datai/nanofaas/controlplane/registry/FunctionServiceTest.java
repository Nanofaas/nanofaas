package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.service.TargetLoadMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FunctionServiceTest {

    private FunctionRegistry registry;
    private QueueManager queueManager;
    private FunctionDefaults defaults;
    private KubernetesResourceManager resourceManager;
    private TargetLoadMetrics targetLoadMetrics;
    private ImageValidator imageValidator;
    private FunctionService service;

    @BeforeEach
    void setUp() {
        registry = new FunctionRegistry();
        queueManager = mock(QueueManager.class);
        defaults = new FunctionDefaults(30000, 4, 100, 3);
        resourceManager = mock(KubernetesResourceManager.class);
        targetLoadMetrics = mock(TargetLoadMetrics.class);
        imageValidator = mock(ImageValidator.class);
        service = new FunctionService(registry, queueManager, defaults, resourceManager, targetLoadMetrics, imageValidator);
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
        verify(queueManager).getOrCreate(any());
        verify(targetLoadMetrics).update(any());
    }

    @Test
    void register_duplicate_returnsEmptyAndDeprovisions() {
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");

        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        service.register(spec);
        Optional<FunctionSpec> dup = service.register(spec);

        assertTrue(dup.isEmpty());
        verify(resourceManager, times(2)).provision(any());
        verify(resourceManager).deprovision("fn");
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
    void register_alwaysValidatesImageEvenOnConflict() {
        when(resourceManager.provision(any())).thenReturn("http://fn-svc:8080");
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        service.register(spec);
        service.register(spec);

        verify(imageValidator, times(2)).validate(resolved("fn", "img:latest", ExecutionMode.DEPLOYMENT));
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
                        java.util.List.of(new it.unimib.datai.nanofaas.common.model.ScalingMetric("queue_depth", "5", null))
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
        verify(queueManager).remove("fn");
        verify(targetLoadMetrics).remove("fn");
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
}
