package it.unimib.datai.nanofaas.common.model;

import java.util.List;

public record ScalingConfig(
        ScalingStrategy strategy,
        Integer minReplicas,
        Integer maxReplicas,
        List<ScalingMetric> metrics
) {
}
