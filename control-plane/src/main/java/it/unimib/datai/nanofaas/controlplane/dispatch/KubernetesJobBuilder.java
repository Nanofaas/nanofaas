package it.unimib.datai.nanofaas.controlplane.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KubernetesJobBuilder {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> RESERVED_ENV = Set.of(
            "FUNCTION_NAME", "EXECUTION_ID", "TRACE_ID", "TIMEOUT_MS",
            "INVOCATION_TIMEOUT_MS", "EXECUTION_MODE", "WATCHDOG_CMD",
            "CALLBACK_URL", "INVOCATION_PAYLOAD"
    );

    private final KubernetesProperties properties;

    public KubernetesJobBuilder(KubernetesProperties properties) {
        this.properties = properties;
    }

    public Job build(InvocationTask task) {
        FunctionSpec spec = task.functionSpec();
        Map<String, String> env = spec.env();
        List<io.fabric8.kubernetes.api.model.EnvVar> envVars = new ArrayList<>();

        // Core identification
        envVars.add(new EnvVarBuilder().withName("FUNCTION_NAME").withValue(task.functionName()).build());
        envVars.add(new EnvVarBuilder().withName("EXECUTION_ID").withValue(task.executionId()).build());
        if (task.traceId() != null) {
            envVars.add(new EnvVarBuilder().withName("TRACE_ID").withValue(task.traceId()).build());
        }

        // Watchdog configuration
        envVars.add(new EnvVarBuilder().withName("TIMEOUT_MS")
                .withValue(String.valueOf(spec.timeoutMs()))
                .build());
        // Keep legacy name for backward compatibility
        envVars.add(new EnvVarBuilder().withName("INVOCATION_TIMEOUT_MS")
                .withValue(String.valueOf(spec.timeoutMs()))
                .build());

        // Runtime mode for watchdog (HTTP, STDIO, FILE)
        if (spec.runtimeMode() != null) {
            envVars.add(new EnvVarBuilder().withName("EXECUTION_MODE")
                    .withValue(spec.runtimeMode().name())
                    .build());
        }

        // Custom runtime command (overrides default)
        if (spec.runtimeCommand() != null && !spec.runtimeCommand().isBlank()) {
            envVars.add(new EnvVarBuilder().withName("WATCHDOG_CMD")
                    .withValue(spec.runtimeCommand())
                    .build());
        }

        String callbackUrl = properties.callbackUrl() == null ? "" : properties.callbackUrl();
        envVars.add(new EnvVarBuilder().withName("CALLBACK_URL")
                .withValue(callbackUrl)
                .build());

        // Serialize invocation payload for watchdog
        try {
            String payload = OBJECT_MAPPER.writeValueAsString(task.request());
            envVars.add(new EnvVarBuilder().withName("INVOCATION_PAYLOAD")
                    .withValue(payload)
                    .build());
        } catch (JsonProcessingException e) {
            // Fallback to empty payload - watchdog will handle gracefully
            envVars.add(new EnvVarBuilder().withName("INVOCATION_PAYLOAD")
                    .withValue("{\"input\":null}")
                    .build());
        }

        if (env != null) {
            env.forEach((key, value) -> {
                if (!RESERVED_ENV.contains(key)) {
                    envVars.add(new EnvVarBuilder().withName(key).withValue(value).build());
                }
            });
        }

        ResourceRequirementsBuilder resources = new ResourceRequirementsBuilder();
        if (spec.resources() != null) {
            if (spec.resources().cpu() != null) {
                resources.addToRequests("cpu", new io.fabric8.kubernetes.api.model.Quantity(spec.resources().cpu()));
                resources.addToLimits("cpu", new io.fabric8.kubernetes.api.model.Quantity(spec.resources().cpu()));
            }
            if (spec.resources().memory() != null) {
                resources.addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity(spec.resources().memory()));
                resources.addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity(spec.resources().memory()));
            }
        }

        return new JobBuilder()
                .withNewMetadata()
                .withGenerateName("fn-" + task.functionName() + "-")
                .addToLabels("app", "nanofaas")
                .addToLabels("function", task.functionName())
                .addToLabels("executionId", task.executionId())
                .addToAnnotations("traceId", task.traceId() == null ? "" : task.traceId())
                .addToAnnotations("idempotencyKey", task.idempotencyKey() == null ? "" : task.idempotencyKey())
                .endMetadata()
                .withSpec(new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withSpec(new PodSpecBuilder()
                                        .addNewContainer()
                                        .withName("function")
                                        .withImage(spec.image())
                                        .withCommand(spec.command() == null || spec.command().isEmpty() ? null : spec.command())
                                        .withEnv(envVars)
                                        .withResources(resources.build())
                                        .endContainer()
                                        .withRestartPolicy("Never")
                                        .build())
                                .build())
                        .build())
                .build();
    }
}
