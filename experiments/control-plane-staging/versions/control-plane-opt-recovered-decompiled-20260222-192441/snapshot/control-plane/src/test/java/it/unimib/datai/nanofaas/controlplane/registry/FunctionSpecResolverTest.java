package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FunctionSpecResolverTest {

    private final FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);
    private final FunctionSpecResolver resolver = new FunctionSpecResolver(defaults);

    @Test
    void resolve_fillsDefaultsForNullFields() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, null, null, null, null);

        FunctionSpec resolved = resolver.resolve(spec);

        assertEquals("fn", resolved.name());
        assertEquals(List.of(), resolved.command());
        assertEquals(Map.of(), resolved.env());
        assertEquals(30000, resolved.timeoutMs());
        assertEquals(4, resolved.concurrency());
        assertEquals(100, resolved.queueSize());
        assertEquals(3, resolved.maxRetries());
        assertEquals(ExecutionMode.DEPLOYMENT, resolved.executionMode());
        assertEquals(RuntimeMode.HTTP, resolved.runtimeMode());
    }

    @Test
    void resolve_preservesExplicitValues() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", List.of("java"), Map.of("K", "V"),
                null, 5000, 2, 50, 1, "http://svc", ExecutionMode.POOL,
                RuntimeMode.STDIO, "cmd", null);

        FunctionSpec resolved = resolver.resolve(spec);

        assertEquals(List.of("java"), resolved.command());
        assertEquals(Map.of("K", "V"), resolved.env());
        assertEquals(5000, resolved.timeoutMs());
        assertEquals(2, resolved.concurrency());
        assertEquals(50, resolved.queueSize());
        assertEquals(1, resolved.maxRetries());
        assertEquals(ExecutionMode.POOL, resolved.executionMode());
        assertEquals(RuntimeMode.STDIO, resolved.runtimeMode());
    }

    @Test
    void resolve_deploymentMode_defaultScaling() {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        FunctionSpec resolved = resolver.resolve(spec);

        assertNotNull(resolved.scalingConfig());
        assertEquals(ScalingStrategy.INTERNAL, resolved.scalingConfig().strategy());
        assertEquals(1, resolved.scalingConfig().minReplicas());
        assertEquals(10, resolved.scalingConfig().maxReplicas());
        assertEquals(1, resolved.scalingConfig().metrics().size());
        assertEquals("queue_depth", resolved.scalingConfig().metrics().get(0).type());
        assertNotNull(resolved.scalingConfig().concurrencyControl());
        assertEquals(ConcurrencyControlMode.FIXED, resolved.scalingConfig().concurrencyControl().mode());
    }

    @Test
    void resolve_deploymentMode_partialScalingConfig() {
        ScalingConfig partial = new ScalingConfig(null, null, 5, null);
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, partial);

        FunctionSpec resolved = resolver.resolve(spec);

        assertEquals(ScalingStrategy.INTERNAL, resolved.scalingConfig().strategy());
        assertEquals(1, resolved.scalingConfig().minReplicas());
        assertEquals(5, resolved.scalingConfig().maxReplicas());
    }

    @Test
    void resolve_nonDeploymentMode_scalingPassedThrough() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 2, 20, List.of());
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.LOCAL, null, null, config);

        FunctionSpec resolved = resolver.resolve(spec);

        assertSame(config, resolved.scalingConfig());
    }

    @Test
    void resolve_staticPerPod_defaultsMissingTargetTo2() {
        ConcurrencyControlConfig cc = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5, List.of(), cc);
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, scaling);

        FunctionSpec resolved = resolver.resolve(spec);

        assertEquals(ConcurrencyControlMode.STATIC_PER_POD, resolved.scalingConfig().concurrencyControl().mode());
        assertEquals(2, resolved.scalingConfig().concurrencyControl().targetInFlightPerPod());
    }

    @Test
    void resolve_staticPerPod_clampsInvalidMinMaxAndTarget() {
        ConcurrencyControlConfig cc = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD,
                50,
                10,
                3,
                null,
                null,
                null,
                null
        );
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5, List.of(), cc);
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, scaling);

        FunctionSpec resolved = resolver.resolve(spec);
        ConcurrencyControlConfig normalized = resolved.scalingConfig().concurrencyControl();

        assertEquals(3, normalized.minTargetInFlightPerPod());
        assertEquals(3, normalized.maxTargetInFlightPerPod());
        assertEquals(3, normalized.targetInFlightPerPod());
    }
}
