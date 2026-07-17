package it.unimib.datai.nanofaas.modules.offload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled         master switch; even when true the gateway stays inert
 *                        until a target URL is configured
 * @param targetUrl       base URL of the remote nanofaas instance
 *                        (e.g. {@code http://cloud-host:8080})
 * @param pressureEnabled switch for pressure-based offload (sync-queue DEPTH/EST_WAIT
 *                        rejections); eager per-function policies work regardless
 */
@ConfigurationProperties(prefix = "nanofaas.offload")
public record OffloadProperties(
        Boolean enabled,
        String targetUrl,
        Boolean pressureEnabled
) {
    public OffloadProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (pressureEnabled == null) {
            pressureEnabled = true;
        }
    }

    public boolean hasTarget() {
        return targetUrl != null && !targetUrl.isBlank();
    }
}
