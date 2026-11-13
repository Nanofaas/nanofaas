package it.unimib.datai.nanofaas.controlplane.json;

import it.unimib.datai.nanofaas.common.model.RawJson;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;

/**
 * Turns JSON into a {@link RawJson} without building objects for it: one pass over the tokens
 * proves it well-formed and counts its shape, so the admission limits can be applied later without
 * walking it again.
 *
 * <p>The counts are the ones the object-tree path arrives at for the same document, so a limit
 * means the same thing for both (pinned by {@code RawJsonDeserializerTest}):
 * <ul>
 *   <li>depth - the root value is at 0, the inside of each container one deeper, empty or not;</li>
 *   <li>nodes - every value, plus one per container, plus one per object key;</li>
 *   <li>width - per container, members + 1 for an array and 2 x members + 1 for an object.</li>
 * </ul>
 */
public final class RawJsonReader {
    private static final JsonMapper JSON = JsonMapper.shared();

    private RawJsonReader() {
    }

    /**
     * A whole document - a function's response body - kept as the bytes it arrived in.
     *
     * @throws IllegalArgumentException if it is empty or holds anything after its one value
     * @throws tools.jackson.core.JacksonException if it is not well-formed JSON
     */
    public static RawJson read(byte[] document) {
        try (JsonParser parser = JSON.createParser(document)) {
            if (parser.nextToken() == null) {
                throw new IllegalArgumentException("empty JSON document");
            }
            int[] shape = shape(parser, null);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("content after the end of the JSON document");
            }
            return RawJson.of(document, shape[0], shape[1], shape[2]);
        }
    }

    /**
     * The value the parser is positioned on, cut out of {@code source} - the bytes that parser is
     * reading - exactly as written. Returns null, having consumed nothing, when the value is a
     * number or literal (too small to be worth it) or the parser's positions do not line up with
     * {@code source}; the caller then copies the tokens instead.
     */
    public static RawJson slice(JsonParser parser, byte[] source) {
        JsonToken token = parser.currentToken();
        long start = parser.currentTokenLocation().getByteOffset();
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            if (!at(source, start, token == JsonToken.START_OBJECT ? '{' : '[')) {
                return null;
            }
            int[] shape = shape(parser, null);
            long end = parser.currentTokenLocation().getByteOffset();
            if (!at(source, end, token == JsonToken.START_OBJECT ? '}' : ']')) {
                throw new IllegalStateException("JSON value does not end where the parser left it");
            }
            return RawJson.of(source, (int) start, (int) end + 1, shape[0], shape[1], shape[2]);
        }
        if (token == JsonToken.VALUE_STRING && at(source, start, '"')) {
            // The parser has not read the string yet (it will, and reject a malformed one, when
            // it moves on); find where it closes. Every escape is a backslash plus one byte
            // that cannot itself close the string.
            int end = (int) start + 1;
            while (end < source.length && source[end] != '"') {
                end += source[end] == '\\' ? 2 : 1;
            }
            if (end >= source.length) {
                throw new IllegalArgumentException("unterminated JSON string");
            }
            return RawJson.of(source, (int) start, end + 1, 1, 0, 0);
        }
        return null;
    }

    private static boolean at(byte[] source, long offset, char expected) {
        return offset >= 0 && offset < source.length && source[(int) offset] == expected;
    }

    /**
     * Consumes the value the parser is positioned on, optionally copying its tokens.
     *
     * @return nodes, depth and width, in that order
     */
    public static int[] shape(JsonParser parser, JsonGenerator copyTo) {
        int[] members = new int[16];
        boolean[] object = new boolean[16];
        int level = 0;
        int depth = 0;
        int nodes = 0;
        int width = 0;
        JsonToken token = parser.currentToken();
        do {
            if (copyTo != null) {
                copy(parser, token, copyTo);
            }
            switch (token) {
                case START_OBJECT, START_ARRAY -> {
                    nodes += 2;
                    // One deeper than the container itself even when it is empty: the tree
                    // path keeps a marker inside every container, at its members' depth.
                    depth = Math.max(depth, level + 1);
                    if (level > 0) members[level]++;
                    if (++level == members.length) {
                        members = Arrays.copyOf(members, level * 2);
                        object = Arrays.copyOf(object, level * 2);
                    }
                    members[level] = 0;
                    object[level] = token == JsonToken.START_OBJECT;
                }
                case END_OBJECT, END_ARRAY -> {
                    width = Math.max(width, object[level] ? 2 * members[level] + 1 : members[level] + 1);
                    level--;
                }
                case PROPERTY_NAME -> nodes++;
                default -> {
                    nodes++;
                    depth = Math.max(depth, level);
                    if (level > 0) members[level]++;
                }
            }
        } while (level > 0 && (token = parser.nextToken()) != null);
        return new int[]{nodes, depth, width};
    }

    private static void copy(JsonParser parser, JsonToken token, JsonGenerator copyTo) {
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            // The digits as written: exact, and without parsing them into a number first.
            copyTo.writeRawValue(parser.getStringCharacters(), parser.getStringOffset(), parser.getStringLength());
        } else {
            copyTo.copyCurrentEvent(parser);
        }
    }
}
