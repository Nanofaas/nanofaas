package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;

/**
 * Capabilities of a control-plane build, derived from its OpenAPI contract.
 *
 * <p>Each boolean reflects the presence of a specific route + HTTP method in the
 * control-plane's {@code /openapi.yaml} document. Optional CLI commands guard on
 * these at execution time so that {@code --help} remains offline.</p>
 */
public record ControlPlaneCapabilities(
        boolean functionUpdate,
        boolean replicas,
        boolean asyncInvocation,
        boolean buildMetadata,
        boolean runtimeConfig
) {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static ControlPlaneCapabilities fromOpenApi(String source) {
        try {
            JsonNode paths = YAML.readTree(source).path("paths");
            return new ControlPlaneCapabilities(
                    has(paths, "/v1/functions/{name}", "patch"),
                    has(paths, "/v1/functions/{name}/replicas", "get")
                            && has(paths, "/v1/functions/{name}/replicas", "put"),
                    has(paths, "/v1/functions/{name}:enqueue", "post"),
                    has(paths, "/modules/build-metadata", "get"),
                    has(paths, "/v1/admin/runtime-config", "get")
                            && has(paths, "/v1/admin/runtime-config/{namespace}", "patch")
                            && has(paths, "/v1/admin/runtime-config/{namespace}/validate", "post"));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid control-plane OpenAPI document", e);
        }
    }

    private static boolean has(JsonNode paths, String path, String method) {
        return paths.path(path).has(method);
    }
}
