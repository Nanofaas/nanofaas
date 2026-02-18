package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TargetLoadMetricsTest {

    @Test
    void update_registersGaugePerScalingType_andRemoveCleansUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TargetLoadMetrics metrics = new TargetLoadMetrics(registry);

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "img",
                List.of(),
                null,
                null,
                1000,
                1,
                10,
                0,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        List.of(
                                new ScalingMetric("queue_depth", "5", null),
                                new ScalingMetric("rps", "12", null),
                                new ScalingMetric("in_flight", "3", null)
                        )
                )
        );

        metrics.update(spec);

        Gauge queue = registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "queue")
                .gauge();
        assertNotNull(queue);
        assertEquals(5.0, queue.value());

        Gauge rps = registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "rps")
                .gauge();
        assertNotNull(rps);
        assertEquals(12.0, rps.value());

        Gauge cap = registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "capacity")
                .gauge();
        assertNotNull(cap);
        assertEquals(3.0, cap.value());

        metrics.remove("echo");

        assertNull(registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "queue")
                .gauge());
        assertNull(registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "rps")
                .gauge());
        assertNull(registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "capacity")
                .gauge());
    }
}

