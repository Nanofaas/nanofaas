package it.unimib.datai.nanofaas.sdk.runtime;

import tools.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;

import java.util.Map;

public record CallbackPayload(
        boolean success,
        JsonNode output,
        ErrorInfo error,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Integer statusCode,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Map<String, String> headers,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String encoding
) {
    public static CallbackPayload success(JsonNode output) {
        return new CallbackPayload(true, output, null, null, null, null);
    }

    public static CallbackPayload successWithEnvelope(JsonNode output, Integer statusCode,
                                                        Map<String, String> headers, String encoding) {
        return new CallbackPayload(true, output, null, statusCode, headers, encoding);
    }

    public static CallbackPayload error(String code, String message) {
        return new CallbackPayload(false, null, new ErrorInfo(code, message), null, null, null);
    }
}
