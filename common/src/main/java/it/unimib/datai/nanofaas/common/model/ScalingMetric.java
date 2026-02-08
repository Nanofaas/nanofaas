package it.unimib.datai.nanofaas.common.model;

public record ScalingMetric(
        String type,
        String target,
        String query
) {
}
