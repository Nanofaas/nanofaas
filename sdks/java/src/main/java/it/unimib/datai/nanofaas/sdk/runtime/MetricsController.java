package it.unimib.datai.nanofaas.sdk.runtime;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus scrape endpoint.
 *
 * <p>This endpoint exists because function containers need a stable scrape path that external
 * watchdogs can discover without knowing Spring Actuator internals. It depends on the Prometheus
 * registry being present, but it remains part of the runtime contract even when the registry is not
 * configured.</p>
 */
@RestController
public class MetricsController {
    private static final String PROM_004 = "text/plain; version=0.0.4; charset=utf-8";

    private final ObjectProvider<PrometheusMeterRegistry> registryProvider;

    public MetricsController(ObjectProvider<PrometheusMeterRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @GetMapping(value = "/metrics", produces = PROM_004)
    public ResponseEntity<String> metrics() {
        PrometheusMeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            // Keep the endpoint present even if Prometheus is disabled/misconfigured.
            return ResponseEntity.status(503)
                    .contentType(MediaType.parseMediaType(PROM_004))
                    .body("# Prometheus registry not configured\n");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(PROM_004))
                .body(registry.scrape());
    }
}
