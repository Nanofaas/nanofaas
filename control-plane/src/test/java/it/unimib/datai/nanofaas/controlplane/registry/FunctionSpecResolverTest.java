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
}
