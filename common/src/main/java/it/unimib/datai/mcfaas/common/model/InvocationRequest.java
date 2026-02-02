package com.mcfaas.common.model;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record InvocationRequest(
        @NotNull(message = "Input payload is required")
        Object input,
        Map<String, String> metadata
) {
}
