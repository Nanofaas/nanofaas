package it.unimib.datai.nanofaas.controlplane.offload;

/**
 * Raised when an invocation was offloaded and the remote call failed.
 * Preserves remote429/503/504; other failures map to502. No local fallback.
 */
public class OffloadFailedException extends RuntimeException {
    private final String targetUrl;
    private final boolean gatewayTimeout;
    private final int statusCode;
    private final String retryAfter;

    public OffloadFailedException(String targetUrl, boolean gatewayTimeout, String message) {
        this(targetUrl, gatewayTimeout ? 504 : 502, null, message);
    }

    public OffloadFailedException(String targetUrl, int statusCode, String retryAfter, String message) {
        super(message);
        this.targetUrl = targetUrl;
        this.statusCode = statusCode == 429 || statusCode == 503 || statusCode == 504 ? statusCode : 502;
        this.gatewayTimeout = this.statusCode == 504;
        this.retryAfter = retryAfter;
    }

    public int statusCode() {
        return statusCode;
    }

    public String retryAfter() {
        return retryAfter;
    }

    public String targetUrl() {
        return targetUrl;
    }

    public boolean gatewayTimeout() {
        return gatewayTimeout;
    }
}
