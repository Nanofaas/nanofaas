package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simple health endpoint for watchdog defaults and K8s probes.
 *
 * <p>We intentionally expose {@code GET /health} (not only {@code /actuator/health}) so that
 * lightweight watchdogs and generic probes can work without Spring-specific paths.</p>
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

