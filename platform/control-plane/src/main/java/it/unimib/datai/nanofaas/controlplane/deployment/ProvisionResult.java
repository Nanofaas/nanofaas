package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

import java.util.Map;

public record ProvisionResult(
        String endpointUrl,
        String backendId,
        ExecutionMode effectiveExecutionMode,
        String degradationReason,
        Map<String, String> metadata
) {
    public ProvisionResult(String endpointUrl, String backendId) {
        this(endpointUrl, backendId, ExecutionMode.DEPLOYMENT, null, Map.of());
    }
}
