package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simple health endpoint for watchdog defaults and K8s probes.
 *
 * <p>This endpoint exists because the function container is often probed by generic watchdogs that
 * should not need Spring Actuator conventions. It depends on no other runtime state and is intended
 * to stay alive for the full life of the container, independent of handler registration or callback
 * success.</p>
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
