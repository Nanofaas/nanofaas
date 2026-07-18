package it.unimib.datai.nanofaas.common.model;

/**
 * Per-function offload policy. Absent block: the function follows the global
 * offload defaults (pressure-based offload when the offload module is active).
 *
 * @param enabled   {@code false} opts the function out of offloading entirely
 * @param targetUrl overrides the globally configured remote nanofaas base URL
 * @param mode      {@code "pressure"} (default) or {@code "always"} (eager offload)
 */
public record OffloadPolicy(
        Boolean enabled,
        String targetUrl,
        String mode
) {
    public static final String MODE_PRESSURE = "pressure";
    public static final String MODE_ALWAYS = "always";
}
