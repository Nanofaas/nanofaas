package it.unimib.datai.nanofaas.cli.commands.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneHttpException;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps runtime-config admin HTTP errors to user-facing messages.
 *
 * <p>Only 404 (admin API disabled), 409 (stale revision), and 422 (validation) are mapped;
 * every other status is re-thrown unchanged.</p>
 */
final class RuntimeConfigErrorMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private RuntimeConfigErrorMapper() {}

    static RuntimeException map(ControlPlaneHttpException e) {
        return switch (e.getStatus()) {
            case 404 -> new IllegalArgumentException(
                    "Runtime configuration administration is disabled, or the namespace does not exist. "
                            + "Check the namespace spelling, or enable the admin API with "
                            + "nanofaas.admin.runtime-config.enabled=true on the control plane.");
            case 409 -> new IllegalArgumentException(staleRevisionMessage(e.getBody()));
            case 422 -> new IllegalArgumentException(validationMessage(e.getBody()));
            default -> e;
        };
    }

    private static String staleRevisionMessage(String body) {
        Long current = currentRevision(body);
        return current == null
                ? "Runtime configuration changed concurrently; re-read the current revision and retry."
                : "Runtime configuration changed concurrently; the expected revision is stale "
                        + "(current revision is " + current + "). Re-read the config and retry.";
    }

    private static String validationMessage(String body) {
        return "Runtime configuration is invalid: " + String.join("; ", errors(body));
    }

    private static Long currentRevision(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body).path("currentRevision");
            return node.isMissingNode() || node.isNull() ? null : node.asLong();
        } catch (Exception _) {
            return null;
        }
    }

    private static List<String> errors(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(body).path("errors");
            List<String> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode error : node) {
                    result.add(error.asText());
                }
            }
            return result;
        } catch (Exception _) {
            return List.of();
        }
    }
}
