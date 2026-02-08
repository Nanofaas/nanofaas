package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KubernetesJobBuilderTest {
    @Test
    void issue012_jobTemplateIncludesImageAndEnv() {
        KubernetesProperties properties = new KubernetesProperties("nanofaas", "http://control-plane/v1/internal/executions", 10, null);
        KubernetesJobBuilder builder = new KubernetesJobBuilder(properties);

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "nanofaas/function-runtime:0.5.0",
                List.of("java", "-jar", "app.jar"),
                Map.of("FOO", "bar"),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.REMOTE,
                null,
                null,
                null
        );

        InvocationTask task = new InvocationTask(
                "exec-123",
                "echo",
                spec,
                new InvocationRequest("hi", Map.of()),
                "idem",
                "trace",
                Instant.now(),
                1
        );

        Job job = builder.build(task);
        assertEquals("nanofaas/function-runtime:0.5.0", job.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertNotNull(job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv());
    }
}
