package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ControlPlaneError(String code, String message) {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public static ControlPlaneError fromBody(String body) {
        if (body == null || body.isBlank()) {
            return new ControlPlaneError(null, null);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String code = root.path("error").isMissingNode() ? null : root.path("error").asText(null);
            String message = root.path("message").isMissingNode() ? null : root.path("message").asText(null);
            return new ControlPlaneError(code, message);
        } catch (Exception ignored) {
            return new ControlPlaneError(null, null);
        }
    }
}
