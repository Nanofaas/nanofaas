package com.mcfaas.common.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record FunctionSpec(
        @NotBlank String name,
        @NotBlank String image,
        List<String> command,
        Map<String, String> env,
        ResourceSpec resources,
        @Min(1) Integer timeoutMs,
        @Min(1) Integer concurrency,
        @Min(1) Integer queueSize,
        @Min(0) Integer maxRetries,
        String endpointUrl,
        ExecutionMode executionMode,
        RuntimeMode runtimeMode,
        String runtimeCommand
) {
}
