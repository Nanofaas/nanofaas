package it.unimib.datai.nanofaas.common.model;

import jakarta.validation.constraints.Min;
import java.util.List;

public record ScalingConfig(
        ScalingStrategy strategy,
        @Min(0) Integer minReplicas,
        @Min(0) Integer maxReplicas,
        List<ScalingMetric> metrics,
        ConcurrencyControlConfig concurrencyControl
) {
    public ScalingConfig(
            ScalingStrategy strategy,
            Integer minReplicas,
            Integer maxReplicas,
            List<ScalingMetric> metrics
    ) {
        this(strategy, minReplicas, maxReplicas, metrics, null);
    }
}
