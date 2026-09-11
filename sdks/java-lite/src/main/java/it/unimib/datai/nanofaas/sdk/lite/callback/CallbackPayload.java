package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import java.util.Map;

/** Wire projection: required result fields retain nulls; absent envelope extensions do not. */
public record CallbackPayload(
        boolean success, Object output, ErrorInfo error,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer statusCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> headers,
        @JsonInclude(JsonInclude.Include.NON_NULL) String encoding) {
    public static CallbackPayload from(InvocationResult result) {
        return new CallbackPayload(result.success(), result.output(), result.error(),
                result.statusCode(), result.headers(), result.encoding());
    }
}
