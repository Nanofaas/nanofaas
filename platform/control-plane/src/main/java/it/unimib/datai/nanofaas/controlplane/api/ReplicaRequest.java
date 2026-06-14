package it.unimib.datai.nanofaas.controlplane.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReplicaRequest(
        @NotNull @Min(0) Integer replicas
) {
}
