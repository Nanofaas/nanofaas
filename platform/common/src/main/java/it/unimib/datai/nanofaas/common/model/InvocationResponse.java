package it.unimib.datai.nanofaas.common.model;

public record InvocationResponse(
        String executionId,
        String status,
        Object output,
        ErrorInfo error
) {
}
