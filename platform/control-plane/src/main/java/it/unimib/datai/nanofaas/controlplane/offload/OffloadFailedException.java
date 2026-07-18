package it.unimib.datai.nanofaas.controlplane.offload;

/**
 * Raised when an invocation was offloaded and the remote call failed.
 * Mapped to 502 (or 504 when {@link #gatewayTimeout()}) — no local fallback.
 */
public class OffloadFailedException extends RuntimeException {
    private final String targetUrl;
    private final boolean gatewayTimeout;

    public OffloadFailedException(String targetUrl, boolean gatewayTimeout, String message) {
        super(message);
        this.targetUrl = targetUrl;
        this.gatewayTimeout = gatewayTimeout;
    }

    public String targetUrl() {
        return targetUrl;
    }

    public boolean gatewayTimeout() {
        return gatewayTimeout;
    }
}
