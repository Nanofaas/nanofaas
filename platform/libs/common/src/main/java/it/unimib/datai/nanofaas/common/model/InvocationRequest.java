package it.unimib.datai.nanofaas.common.model;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record InvocationRequest(
        @NotNull(message = "Input payload is required")
        Object input,
        Map<String, String> metadata,
        Map<String, String> headers
) {
    public InvocationRequest(Object input, Map<String, String> metadata) {
        this(input, metadata, null);
    }
}
