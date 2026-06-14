package it.unimib.datai.nanofaas.modules.runtimeconfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/runtime-config")
@ConditionalOnProperty(name = "nanofaas.admin.runtime-config.enabled", havingValue = "true")
public class AdminRuntimeConfigController {

    private final RuntimeConfigService configService;
    private final RuntimeConfigValidator validator;
    private final RuntimeConfigApplier applier;

    public AdminRuntimeConfigController(RuntimeConfigService configService,
                                         RuntimeConfigValidator validator,
                                         RuntimeConfigApplier applier) {
        this.configService = configService;
        this.validator = validator;
        this.applier = applier;
    }

    @GetMapping
    public ResponseEntity<ConfigSnapshotResponse> get() {
        return ResponseEntity.ok(ConfigSnapshotResponse.from(configService.getSnapshot()));
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody PatchRequest request) {
        RuntimeConfigPatch patch;
        try {
            patch = request.toPatch();
        } catch (InvalidPatchRequestException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "field", e.fieldName(),
                    "value", e.value()
            ));
        }
        List<String> errors = validator.validate(configService.getSnapshot().applyPatch(patch));
        if (!errors.isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("errors", errors));
        }
        return ResponseEntity.ok(Map.of("valid", true));
    }

    @PatchMapping
    public ResponseEntity<?> patch(@RequestBody PatchRequest request) {
        if (request.expectedRevision() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "expectedRevision is required"));
        }

        RuntimeConfigPatch patch;
        try {
            patch = request.toPatch();
        } catch (InvalidPatchRequestException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "field", e.fieldName(),
                    "value", e.value()
            ));
        }
        List<String> errors = validator.validate(configService.getSnapshot().applyPatch(patch));
        if (!errors.isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("errors", errors));
        }

        RuntimeConfigSnapshot previous = configService.getSnapshot();
        RuntimeConfigSnapshot updated;
        try {
            updated = configService.update(request.expectedRevision(), patch);
        } catch (RevisionMismatchException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage(), "currentRevision", e.getActual()));
        }

        try {
            applier.apply(updated, previous, configService);
        } catch (RuntimeConfigApplyException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Apply failed, rolled back", "detail", e.getMessage()));
        }

        return ResponseEntity.ok(new PatchResponse(
                updated.revision(),
                ConfigSnapshotResponse.from(updated),
                Instant.now().toString(),
                UUID.randomUUID().toString(),
                List.of()
        ));
    }

    // --- DTOs ---

    public record PatchRequest(
            Long expectedRevision,
            Integer rateMaxPerSecond,
            Boolean syncQueueEnabled,
            Boolean syncQueueAdmissionEnabled,
            String syncQueueMaxEstimatedWait,
            String syncQueueMaxQueueWait,
            Integer syncQueueRetryAfterSeconds
    ) {
        RuntimeConfigPatch toPatch() {
            return new RuntimeConfigPatch(
                    rateMaxPerSecond,
                    syncQueueEnabled,
                    syncQueueAdmissionEnabled,
                    parseDuration(syncQueueMaxEstimatedWait, "syncQueueMaxEstimatedWait"),
                    parseDuration(syncQueueMaxQueueWait, "syncQueueMaxQueueWait"),
                    syncQueueRetryAfterSeconds
            );
        }

        private static Duration parseDuration(String rawValue, String fieldName) {
            if (rawValue == null) {
                return null;
            }
            try {
                return Duration.parse(rawValue);
            } catch (DateTimeParseException e) {
                throw new InvalidPatchRequestException(fieldName, rawValue);
            }
        }
    }

    public record ConfigSnapshotResponse(
            long revision,
            int rateMaxPerSecond,
            boolean syncQueueEnabled,
            boolean syncQueueAdmissionEnabled,
            String syncQueueMaxEstimatedWait,
            String syncQueueMaxQueueWait,
            int syncQueueRetryAfterSeconds
    ) {
        static ConfigSnapshotResponse from(RuntimeConfigSnapshot s) {
            return new ConfigSnapshotResponse(
                    s.revision(),
                    s.rateMaxPerSecond(),
                    s.syncQueueEnabled(),
                    s.syncQueueAdmissionEnabled(),
                    s.syncQueueMaxEstimatedWait().toString(),
                    s.syncQueueMaxQueueWait().toString(),
                    s.syncQueueRetryAfterSeconds()
            );
        }
    }

    public record PatchResponse(
            long revision,
            ConfigSnapshotResponse effectiveConfig,
            String appliedAt,
            String changeId,
            List<String> warnings
    ) {
    }

    private static final class InvalidPatchRequestException extends RuntimeException {
        private final String fieldName;
        private final String value;

        private InvalidPatchRequestException(String fieldName, String value) {
            super("Invalid duration for " + fieldName);
            this.fieldName = fieldName;
            this.value = value;
        }

        private String fieldName() {
            return fieldName;
        }

        private String value() {
            return value;
        }
    }
}
