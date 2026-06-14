package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
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
        } catch (IllegalArgumentException ex) {
            throw new OutputSerializationException(
                    "Function output is not JSON-serializable: " + output.getClass().getName(),
                    ex);
        }
    }
}
