package it.unimib.datai.nanofaas.controlplane.offload;

/**
 * Per-request offload context propagated from the HTTP layer.
 *
 * @param offloadedHop true when this invocation was itself received via offload
 *                     (X-NanoFaaS-Offload-Hop header); such requests are never
 *                     re-offloaded (single hop)
 * @param traceparent  W3C Trace Context header passed through as-is, if present
 * @param tracestate   W3C Trace Context header passed through as-is, if present
 */
public record OffloadContext(boolean offloadedHop, String traceparent, String tracestate) {

    private static final OffloadContext NONE = new OffloadContext(false, null, null);

    public static OffloadContext none() {
        return NONE;
    }
}
