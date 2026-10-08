package it.unimib.datai.nanofaas.common.runtime;

import java.util.Map;

/**
 * Optional response envelope a handler may return instead of a plain value to control
 * the HTTP status code, a safe set of response headers, and whether {@code output} is
 * base64-encoded binary. Its mere presence as the handler's return value is itself the
 * signal that the response was function-decided, not a platform error.
 */
public record HandlerResponse(Object output, int statusCode, Map<String, String> headers, String encoding) {
    public static HandlerResponse of(Object output, int statusCode) {
        return new HandlerResponse(output, statusCode, Map.of(), null);
    }

    public static HandlerResponse of(Object output, int statusCode, Map<String, String> headers) {
        return new HandlerResponse(output, statusCode, headers, null);
    }
}
