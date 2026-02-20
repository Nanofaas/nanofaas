package it.unimib.datai.nanofaas.controlplane.dispatch;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpecBuilder;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates nanofaas {@link ScalingMetric} definitions into Kubernetes {@link MetricSpec} objects
 * for use in HorizontalPodAutoscaler resources.
 */
class KubernetesMetricsTranslator {

    List<MetricSpec> toMetricSpecs(ScalingConfig config, FunctionSpec spec) {
        List<MetricSpec> metricSpecs = new ArrayList<>();
        if (config.metrics() == null) {
            return metricSpecs;
        }
        for (ScalingMetric m : config.metrics()) {
            MetricSpec ms = toK8sMetricSpec(m, spec.name());
            if (ms != null) {
                metricSpecs.add(ms);
            }
        }
        return metricSpecs;
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
}
