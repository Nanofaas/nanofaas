package it.unimib.datai.nanofaas.sdk.runtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import org.springframework.stereotype.Component;

@Component
public class JsonOutputNormalizer {
    private final ObjectMapper objectMapper;

    public JsonOutputNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode toJsonNode(Object output) {
        if (output == null) {
            return NullNode.getInstance();
        }
        if (output instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            return objectMapper.valueToTree(output);
        } catch (RuntimeException ex) {
            // ponytail: Jackson 3 throws InvalidDefinitionException, Jackson 2 throws
            // IllegalArgumentException; RuntimeException covers both.
            throw new OutputSerializationException(
                    "Function output is not JSON-serializable: " + output.getClass().getName(),
                    ex);
        }
    }
}
