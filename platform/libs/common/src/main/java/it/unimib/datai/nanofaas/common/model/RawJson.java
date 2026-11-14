package it.unimib.datai.nanofaas.common.model;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.SerializableString;
import tools.jackson.databind.JacksonSerializable;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.jsontype.TypeSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One JSON value kept as UTF-8 text, with the shape facts needed to bound it. The control plane
 * forwards payloads without looking inside them; this is the form they travel in.
 *
 * <p>It writes itself: any Jackson mapper that meets it, in whatever {@code Object}-typed field,
 * emits the text as it is, with no serializer to register.
 */
public final class RawJson implements JacksonSerializable {
    private final byte[] utf8;
    private final int nodes;
    private final int depth;
    private final int width;

    private RawJson(byte[] utf8, int nodes, int depth, int width) {
        this.utf8 = utf8;
        this.nodes = nodes;
        this.depth = depth;
        this.width = width;
    }

    /** {@code utf8} must already be one well-formed JSON value; the counts describe its shape. */
    public static RawJson of(byte[] utf8, int nodes, int depth, int width) {
        return new RawJson(utf8.clone(), nodes, depth, width);
    }

    /** As {@link #of(byte[], int, int, int)}, for a value that is a slice of a larger document. */
    public static RawJson of(byte[] document, int from, int to, int nodes, int depth, int width) {
        return new RawJson(Arrays.copyOfRange(document, from, to), nodes, depth, width);
    }

    public String json() {
        return new String(utf8, StandardCharsets.UTF_8);
    }

    public int length() { return utf8.length; }
    public int nodes() { return nodes; }
    public int depth() { return depth; }
    public int width() { return width; }

    @Override
    public void serialize(JsonGenerator generator, SerializationContext context) {
        generator.writeRawValue(new Text(utf8));
    }

    @Override
    public void serializeWithType(JsonGenerator generator, SerializationContext context, TypeSerializer types) {
        serialize(generator, context);
    }

    @Override public boolean equals(Object other) {
        return other instanceof RawJson that && Arrays.equals(utf8, that.utf8);
    }
    @Override public int hashCode() { return Arrays.hashCode(utf8); }
    @Override public String toString() { return json(); }

    /**
     * Hands the bytes to a byte-oriented generator as they are, so writing costs a copy rather
     * than a decode to chars and an encode back. Only the unquoted forms exist: raw JSON is never
     * written as a quoted string.
     */
    private record Text(byte[] utf8) implements SerializableString {
        @Override public String getValue() { return new String(utf8, StandardCharsets.UTF_8); }
        @Override public int charLength() { return getValue().length(); }
        // A copy: the caller may keep or modify it. Generators prefer the append/write forms.
        @Override public byte[] asUnquotedUTF8() { return utf8.clone(); }

        @Override public int appendUnquotedUTF8(byte[] buffer, int offset) {
            if (utf8.length > buffer.length - offset) {
                return -1;
            }
            System.arraycopy(utf8, 0, buffer, offset, utf8.length);
            return utf8.length;
        }

        @Override public int appendUnquoted(char[] buffer, int offset) {
            String value = getValue();
            if (value.length() > buffer.length - offset) {
                return -1;
            }
            value.getChars(0, value.length(), buffer, offset);
            return value.length();
        }

        @Override public int writeUnquotedUTF8(OutputStream out) throws IOException {
            out.write(utf8);
            return utf8.length;
        }

        @Override public int putUnquotedUTF8(ByteBuffer buffer) {
            if (utf8.length > buffer.remaining()) {
                return -1;
            }
            buffer.put(utf8);
            return utf8.length;
        }

        @Override public char[] asQuotedChars() { throw quoted(); }
        @Override public byte[] asQuotedUTF8() { throw quoted(); }
        @Override public int appendQuotedUTF8(byte[] buffer, int offset) { throw quoted(); }
        @Override public int appendQuoted(char[] buffer, int offset) { throw quoted(); }
        @Override public int writeQuotedUTF8(OutputStream out) { throw quoted(); }
        @Override public int putQuotedUTF8(ByteBuffer buffer) { throw quoted(); }

        private static UnsupportedOperationException quoted() {
            return new UnsupportedOperationException("raw JSON is not a quoted string");
        }
    }
}
