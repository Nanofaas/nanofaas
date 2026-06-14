package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;

public final class ScalingDecisionCalculator {
    private static final double DEFAULT_TARGET = 50.0;

    private final ScalingMetricsReader metricsReader;

    public ScalingDecisionCalculator(ScalingMetricsReader metricsReader) {
        this.metricsReader = metricsReader;
    }

    public ScalingDecision calculate(FunctionSpec spec, int currentReplicas) {
        ScalingConfig scaling = spec.scalingConfig();
        int normalizedCurrentReplicas = currentReplicas <= 0
                ? Math.max(1, scaling.minReplicas())
                : currentReplicas;

        double maxRatio = 0.0;
        if (scaling.metrics() != null) {
            for (ScalingMetric metric : scaling.metrics()) {
                double currentValue = metricsReader.readMetric(spec.name(), metric);
                double targetValue = parseTarget(metric.target());
                if (targetValue > 0) {
                    maxRatio = Math.max(maxRatio, currentValue / targetValue);
                }
            }
        }

        int desiredReplicas = (int) Math.ceil(maxRatio * normalizedCurrentReplicas);
        desiredReplicas = Math.max(scaling.minReplicas(), Math.min(scaling.maxReplicas(), desiredReplicas));

        int effectiveReplicas = currentReplicas <= 0
                ? Math.max(1, scaling.minReplicas())
                : normalizedCurrentReplicas;

        return new ScalingDecision(
                normalizedCurrentReplicas,
                desiredReplicas,
                effectiveReplicas,
                maxRatio,
                desiredReplicas < normalizedCurrentReplicas
        );
    }

    private double parseTarget(String target) {
        try {
            if (target == null || target.isBlank()) {
                return DEFAULT_TARGET;
            }
            return Double.parseDouble(target);
        } catch (RuntimeException ex) {
            return DEFAULT_TARGET;
        }
    }
}
