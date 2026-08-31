package it.unimib.datai.nanofaas.cli.commands.controlplane.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.io.YamlIO;

import java.nio.file.Path;
import java.util.Map;

/**
 * Runtime-configuration patch input, loaded from YAML.
 *
 * <p>An object containing a {@code values} key is treated as an envelope with an optional
 * {@code expectedRevision}; otherwise the whole object is the values map.</p>
 */
public record RuntimeConfigInput(Long expectedRevision, Map<String, Object> values) {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public static RuntimeConfigInput load(Path path) {
        JsonNode root = YamlIO.readTree(path);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Runtime config input must be a YAML object");
        }

        JsonNode valuesNode = root.path("values");
        if (valuesNode.isMissingNode()) {
            return new RuntimeConfigInput(null, asMap(root));
        }
        if (!valuesNode.isObject()) {
            throw new IllegalArgumentException("Runtime config 'values' must be an object");
        }

        JsonNode revisionNode = root.path("expectedRevision");
        Long expectedRevision = revisionNode.isMissingNode() || revisionNode.isNull()
                ? null
                : revisionNode.asLong();
        return new RuntimeConfigInput(expectedRevision, asMap(valuesNode));
    }

    private static Map<String, Object> asMap(JsonNode node) {
        return MAPPER.convertValue(node, new TypeReference<>() {});
    }
}
