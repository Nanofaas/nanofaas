package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesJobBuilderReservedEnvTest {

    @Test
    void reservedEnvVars_areNotOverriddenByUserEnv() {
        KubernetesProperties properties = new KubernetesProperties(
                "default", "http://callback/v1/internal/executions", 10, null);
        KubernetesJobBuilder builder = new KubernetesJobBuilder(properties);

        // User tries to override EXECUTION_ID, CALLBACK_URL, and a normal var
        FunctionSpec spec = new FunctionSpec(
                "echo",
                "nanofaas/function-runtime:0.5.0",
                null,
                Map.of(
                        "EXECUTION_ID", "HACKED",
                        "CALLBACK_URL", "http://evil.com",
                        "MY_CUSTOM_VAR", "allowed"
                ),
                null, 1000, 1, 10, 3, null,
                ExecutionMode.REMOTE, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-123", "echo", spec,
                new InvocationRequest("hi", Map.of()),
                null, null, Instant.now(), 1
        );

        Job job = builder.build(task);
        List<EnvVar> envVars = job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();

        // EXECUTION_ID should be the real one, not "HACKED"
        List<String> executionIdValues = envVars.stream()
                .filter(e -> "EXECUTION_ID".equals(e.getName()))
                .map(EnvVar::getValue)
                .toList();
        assertThat(executionIdValues).hasSize(1);
        assertThat(executionIdValues.get(0)).isEqualTo("exec-123");

        // CALLBACK_URL should be the real one
        List<String> callbackValues = envVars.stream()
                .filter(e -> "CALLBACK_URL".equals(e.getName()))
                .map(EnvVar::getValue)
                .toList();
        assertThat(callbackValues).hasSize(1);
        assertThat(callbackValues.get(0)).isEqualTo("http://callback/v1/internal/executions");

        // MY_CUSTOM_VAR should be present
        assertThat(envVars.stream().anyMatch(e ->
                "MY_CUSTOM_VAR".equals(e.getName()) && "allowed".equals(e.getValue())
        )).isTrue();
    }

    @Test
    void userEnvWithNoReservedKeys_allIncluded() {
        KubernetesProperties properties = new KubernetesProperties(
                "default", "http://callback", 10, null);
        KubernetesJobBuilder builder = new KubernetesJobBuilder(properties);

        FunctionSpec spec = new FunctionSpec(
                "fn", "image", null,
                Map.of("FOO", "bar", "BAZ", "qux"),
                null, 1000, 1, 10, 3, null,
                ExecutionMode.REMOTE, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-1", "fn", spec,
                new InvocationRequest("input", Map.of()),
                null, null, Instant.now(), 1
        );

        Job job = builder.build(task);
        List<EnvVar> envVars = job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();

        assertThat(envVars.stream().anyMatch(e -> "FOO".equals(e.getName()))).isTrue();
        assertThat(envVars.stream().anyMatch(e -> "BAZ".equals(e.getName()))).isTrue();
    }
}
