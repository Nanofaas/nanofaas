package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class RuntimePayloadLimits {
    private final ObjectMapper mapper;
    private final BoundedJson boundedJson;
    private final int maxOutputBytes;

    RuntimePayloadLimits(ObjectMapper mapper,
                         @Value("${nanofaas.output.max-bytes:${NANOFAAS_MAX_OUTPUT_BYTES:1048576}}") int maxOutputBytes) {
        if (maxOutputBytes <= 0) throw new IllegalArgumentException("max output bytes must be positive");
        this.mapper = mapper;
        this.boundedJson = new BoundedJson(mapper);
        this.maxOutputBytes = maxOutputBytes;
    }

    boolean outputTooLarge(Object output) {
        try {
            normalize(output);
            return false;
        } catch (BoundedJson.PayloadTooLargeException ex) {
            return true;
        }
    }

    JsonNode normalize(Object output) {
        final byte[] bytes;
        try {
            bytes = boundedJson.serialize(output, maxOutputBytes);
        } catch (BoundedJson.SerializationException ex) {
            throw new OutputSerializationException(
                    "Function output is not JSON-serializable: "
                            + (output == null ? "null" : output.getClass().getName()), ex);
        }
        try {
            return mapper.readTree(bytes);
        } catch (tools.jackson.core.JacksonException ex) {
            throw new OutputSerializationException("Serialized function output is not valid JSON", ex);
        }
    }
}
