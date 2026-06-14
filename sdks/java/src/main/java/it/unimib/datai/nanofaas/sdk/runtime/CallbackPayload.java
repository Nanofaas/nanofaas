package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;

public record CallbackPayload(
        boolean success,
        JsonNode output,
        ErrorInfo error
) {
    public static CallbackPayload success(JsonNode output) {
        return new CallbackPayload(true, output, null);
    }

    public static CallbackPayload error(String code, String message) {
        return new CallbackPayload(false, null, new ErrorInfo(code, message));
    }
}
