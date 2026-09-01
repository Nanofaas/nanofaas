package it.unimib.datai.nanofaas.modules.runtimeconfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/runtime-config")
@ConditionalOnProperty(name = "nanofaas.admin.runtime-config.enabled", havingValue = "true")
public class AdminRuntimeConfigController {
    private static final String ERROR = "error";

    private final RuntimeConfigService service;

    public AdminRuntimeConfigController(RuntimeConfigService service) {
        this.service = service;
    }

    @GetMapping
    public RuntimeConfigSnapshot get() {
        return service.getSnapshot();
    }

    @GetMapping("/{namespace}")
    public ResponseEntity<Object> getNamespace(@PathVariable("namespace") String namespace) {
        RuntimeConfigSnapshot snapshot = service.getSnapshot();
        return snapshot.namespaces().containsKey(namespace)
                ? ResponseEntity.ok(snapshot.namespaces().get(namespace))
                : ResponseEntity.notFound().build();
    }

    // 422 without an exception is the point of this endpoint: it reports whether a
    // patch would be accepted, and the contract (openapi.yaml) declares that status
    // as the "invalid values" answer. The CLI reads it as an answer, not a failure.
    @SuppressWarnings("java:S6863")
    @PostMapping("/{namespace}/validate")
    public ResponseEntity<Object> validate(@PathVariable("namespace") String namespace, @RequestBody Map<String, Object> values) {
        try {
            List<String> errors = service.validate(namespace, values);
            return errors.isEmpty()
                    ? ResponseEntity.ok(Map.of("valid", true))
                    : ResponseEntity.unprocessableEntity().body(Map.of("errors", errors));
        } catch (UnknownRuntimeConfigNamespaceException _) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{namespace}")
    public ResponseEntity<Object> patch(@PathVariable("namespace") String namespace, @RequestBody PatchRequest request) {
        if (request.expectedRevision() == null || request.values() == null) {
            return ResponseEntity.badRequest().body(Map.of(ERROR, "expectedRevision and values are required"));
        }
        try {
            RuntimeConfigSnapshot updated = service.update(request.expectedRevision(), namespace, request.values());
            return ResponseEntity.ok(new PatchResponse(updated.revision(), updated, Instant.now().toString(),
                    UUID.randomUUID().toString(), List.of()));
        } catch (UnknownRuntimeConfigNamespaceException _) {
            return ResponseEntity.notFound().build();
        } catch (RevisionMismatchException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(ERROR, e.getMessage(), "currentRevision", e.getActual()));
        } catch (RuntimeConfigValidationException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("errors", e.errors()));
        } catch (RuntimeConfigApplyException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(ERROR, "Apply failed, rolled back", "detail", e.getMessage()));
        }
    }

    public record PatchRequest(Long expectedRevision, Map<String, Object> values) {
    }

    public record PatchResponse(long revision, RuntimeConfigSnapshot effectiveConfig, String appliedAt,
                                String changeId, List<String> warnings) {
    }
}
