package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.RawJson;
import it.unimib.datai.nanofaas.controlplane.json.RawJsonReader;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;

/**
 * Reads one JSON value inside a larger document as text instead of building objects for it.
 *
 * <p>When the reader was given the document's bytes (the {@link #SOURCE} attribute) the value is
 * cut out of them exactly as the caller wrote it, at the cost of one pass over its tokens. Without
 * them - a stream, or a mapper used directly - the tokens are copied instead: numbers keep their
 * digits, strings are re-encoded and whitespace is dropped, at about the cost of a parse.
 */
final class RawJsonDeserializer extends ValueDeserializer<RawJson> {
    /** Reader attribute holding the {@code byte[]} the parser is reading from. */
    static final Object SOURCE = RawJsonDeserializer.class;

    private static final JsonMapper GENERATORS = JsonMapper.shared();

    @Override
    public RawJson deserialize(JsonParser parser, DeserializationContext context) {
        if (context.getAttribute(SOURCE) instanceof byte[] source) {
            RawJson sliced = RawJsonReader.slice(parser, source);
            if (sliced != null) {
                return sliced;
            }
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream(256);
        int[] shape;
        try (JsonGenerator generator = GENERATORS.createGenerator(text)) {
            shape = RawJsonReader.shape(parser, generator);
        }
        return RawJson.of(text.toByteArray(), shape[0], shape[1], shape[2]);
    }
}
