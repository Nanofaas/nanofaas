package it.unimib.datai.nanofaas.controlplane.dispatch;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.*;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KubernetesDeploymentBuilder {
    private static final Set<String> RESERVED_ENV = Set.of(
            "FUNCTION_NAME", "WARM", "TIMEOUT_MS", "EXECUTION_MODE", "WATCHDOG_CMD"
    );

    private final KubernetesProperties properties;

    public KubernetesDeploymentBuilder(KubernetesProperties properties) {
        this.properties = properties;
    }

    public static String deploymentName(String functionName) {
        return "fn-" + functionName;
    }

    public static String serviceName(String functionName) {
        return "fn-" + functionName;
    }

    public Deployment buildDeployment(FunctionSpec spec) {
        List<EnvVar> envVars = buildEnvVars(spec);
        ResourceRequirementsBuilder resources = buildResources(spec);
        int replicas = 1;
        if (spec.scalingConfig() != null && spec.scalingConfig().minReplicas() != null) {
            replicas = spec.scalingConfig().minReplicas();
        }

        Map<String, String> labels = Map.of(
                "app", "nanofaas",
                "function", spec.name()
        );

        return new DeploymentBuilder()
                .withNewMetadata()
                    .withName(deploymentName(spec.name()))
                    .addToLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withNewSelector()
                        .addToMatchLabels("function", spec.name())
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .addNewContainer()
                                .withName("function")
                                .withImage(spec.image())
                                .withCommand(spec.command() == null || spec.command().isEmpty() ? null : spec.command())
                                .withEnv(envVars)
                                .withResources(resources.build())
                                .addNewPort()
                                    .withContainerPort(8080)
                                    .withProtocol("TCP")
                                .endPort()
                                .withNewReadinessProbe()
                                    .withNewHttpGet()
                                        .withPath("/invoke")
                                        .withPort(new IntOrString(8080))
                                    .endHttpGet()
                                    .withInitialDelaySeconds(2)
                                    .withPeriodSeconds(5)
                                .endReadinessProbe()
                            .endContainer()
                            .withRestartPolicy("Always")
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    public io.fabric8.kubernetes.api.model.Service buildService(FunctionSpec spec) {
        return new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName(spec.name()))
                    .addToLabels("app", "nanofaas")
                    .addToLabels("function", spec.name())
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .addToSelector("function", spec.name())
                    .addNewPort()
                        .withPort(8080)
                        .withTargetPort(new IntOrString(8080))
                        .withProtocol("TCP")
                    .endPort()
                .endSpec()
                .build();
    }

    public HorizontalPodAutoscaler buildHpa(FunctionSpec spec) {
        ScalingConfig scaling = spec.scalingConfig();
        if (scaling == null || scaling.strategy() != ScalingStrategy.HPA) {
            return null;
        }

        List<MetricSpec> metricSpecs = new ArrayList<>();
        if (scaling.metrics() != null) {
            for (ScalingMetric m : scaling.metrics()) {
                MetricSpec ms = toK8sMetricSpec(m, spec.name());
                if (ms != null) {
                    metricSpecs.add(ms);
                }
            }
        }

        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata()
                    .withName(deploymentName(spec.name()))
                    .addToLabels("app", "nanofaas")
                    .addToLabels("function", spec.name())
                .endMetadata()
                .withNewSpec()
                    .withNewScaleTargetRef()
                        .withApiVersion("apps/v1")
                        .withKind("Deployment")
                        .withName(deploymentName(spec.name()))
                    .endScaleTargetRef()
                    .withMinReplicas(scaling.minReplicas())
                    .withMaxReplicas(scaling.maxReplicas())
                    .withMetrics(metricSpecs)
                .endSpec()
                .build();
    }

    private MetricSpec toK8sMetricSpec(ScalingMetric metric, String functionName) {
        String type = metric.type();
        int targetValue = parseTarget(metric.target());

        return switch (type) {
            case "cpu" -> new MetricSpecBuilder()
                    .withType("Resource")
                    .withNewResource()
                        .withName("cpu")
                        .withNewTarget()
                            .withType("Utilization")
                            .withAverageUtilization(targetValue)
                        .endTarget()
                    .endResource()
                    .build();
            case "memory" -> new MetricSpecBuilder()
                    .withType("Resource")
                    .withNewResource()
                        .withName("memory")
                        .withNewTarget()
                            .withType("Utilization")
                            .withAverageUtilization(targetValue)
                        .endTarget()
                    .endResource()
                    .build();
            case "queue_depth", "in_flight", "rps" -> new MetricSpecBuilder()
                    .withType("External")
                    .withNewExternal()
                        .withNewMetric()
                            .withName("nanofaas_" + type)
                            .withNewSelector()
                                .addToMatchLabels("function", functionName)
                            .endSelector()
                        .endMetric()
                        .withNewTarget()
                            .withType("Value")
                            .withValue(new Quantity(String.valueOf(targetValue)))
                        .endTarget()
                    .endExternal()
                    .build();
            case "prometheus" -> {
                String metricName = "nanofaas_custom_" + functionName;
                yield new MetricSpecBuilder()
                        .withType("External")
                        .withNewExternal()
                            .withNewMetric()
                                .withName(metricName)
                                .withNewSelector()
                                    .addToMatchLabels("function", functionName)
                                .endSelector()
                            .endMetric()
                            .withNewTarget()
                                .withType("Value")
                                .withValue(new Quantity(String.valueOf(targetValue)))
                            .endTarget()
                        .endExternal()
                        .build();
            }
            default -> null;
        };
    }

    private int parseTarget(String target) {
        try {
            return Integer.parseInt(target);
        } catch (NumberFormatException e) {
            return 50;
        }
    }

    private List<EnvVar> buildEnvVars(FunctionSpec spec) {
        List<EnvVar> envVars = new ArrayList<>();

        envVars.add(new EnvVarBuilder().withName("FUNCTION_NAME").withValue(spec.name()).build());
        envVars.add(new EnvVarBuilder().withName("WARM").withValue("true").build());
        envVars.add(new EnvVarBuilder().withName("TIMEOUT_MS")
                .withValue(String.valueOf(spec.timeoutMs())).build());

        if (spec.runtimeMode() != null) {
            envVars.add(new EnvVarBuilder().withName("EXECUTION_MODE")
                    .withValue(spec.runtimeMode().name()).build());
        }

        if (spec.runtimeCommand() != null && !spec.runtimeCommand().isBlank()) {
            envVars.add(new EnvVarBuilder().withName("WATCHDOG_CMD")
                    .withValue(spec.runtimeCommand()).build());
        }

        if (spec.env() != null) {
            spec.env().forEach((key, value) -> {
                if (!RESERVED_ENV.contains(key)) {
                    envVars.add(new EnvVarBuilder().withName(key).withValue(value).build());
                }
            });
        }

        return envVars;
    }

    private ResourceRequirementsBuilder buildResources(FunctionSpec spec) {
        ResourceRequirementsBuilder resources = new ResourceRequirementsBuilder();
        if (spec.resources() != null) {
            if (spec.resources().cpu() != null) {
                resources.addToRequests("cpu", new Quantity(spec.resources().cpu()));
                resources.addToLimits("cpu", new Quantity(spec.resources().cpu()));
            }
            if (spec.resources().memory() != null) {
                resources.addToRequests("memory", new Quantity(spec.resources().memory()));
                resources.addToLimits("memory", new Quantity(spec.resources().memory()));
            }
        }
        return resources;
    }
}
