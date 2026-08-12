package it.unimib.datai.nanofaas.sdk.runtime;

import tools.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;

import java.util.Map;

public record CallbackPayload(
        boolean success,
        JsonNode output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
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
