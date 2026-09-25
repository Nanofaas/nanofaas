package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import org.gradle.api.GradleException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses a recipe YAML file into a schema-validated, read-only JSON tree. */
public final class RecipeReader {

    public static final String SCHEMA_RESOURCE = "recipes/recipe-v1.schema.json";
    public static final String SCHEMA_V2_RESOURCE = "recipes/recipe-v2.schema.json";
    static final String LOCAL_TAG = "local";

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .pathType(PathType.LEGACY).locale(Locale.ENGLISH).build()));
    private static final Map<Integer, Schema> SCHEMAS = Map.of(
            1, REGISTRY.getSchema(SchemaLocation.of("classpath:" + SCHEMA_RESOURCE)),
            2, REGISTRY.getSchema(SchemaLocation.of("classpath:" + SCHEMA_V2_RESOURCE)));
    private static final Schema TAG_SCHEMA = REGISTRY.getSchema(SchemaLocation.of("classpath:" + SCHEMA_V2_RESOURCE + "#/$defs/tag"));

    /**
     * @param data validated recipe normalised to the v2 model; callers must not mutate it
     * @param effectiveTag {@code -PrecipeTag}, else {@code registry.tag}, else {@value #LOCAL_TAG}
     * @param declaredVersion the file's own schemaVersion (1 or 2)
     */
    public record Document(Path source, JsonNode data, String sourceSha256, String effectiveTag, int declaredVersion) {
    }

    /** @param tagOverride value of {@code -PrecipeTag}, or null when absent */
    public Document read(Path file, String tagOverride) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw failure(file, "cannot read recipe (" + exception + ")");
        }
        JsonNode data = toJson(file, parse(file, bytes), new StringBuilder(), new IdentityHashMap<>());
        if (!data.isObject()) {
            throw failure(file, "(root): recipe must be an object");
        }
        JsonNode version = data.path("schemaVersion");
        Schema schema = version.isIntegralNumber() ? SCHEMAS.get(version.asInt()) : null;
        if (schema == null) {
            throw failure(file, "schemaVersion: supported versions are 1 and 2");
        }
        List<String> errors = schema.validate(data).stream()
                .map(error -> location(error.getInstanceLocation().toString()) + ": " + error.getMessage())
                .distinct().sorted().toList();
        if (!errors.isEmpty()) {
            throw failure(file, String.join("\n  ", errors));
        }
        // A valid v1 document is a v2 document without the v2-only fields: only its version differs.
        ObjectNode normalised = ((ObjectNode) data).deepCopy().put("schemaVersion", 2);
        normaliseOptimization(normalised.path("controlPlane"));
        for (String collection : List.of("functions", "services")) {
            normalised.path(collection).forEach(RecipeReader::normaliseOptimization);
        }
        return new Document(file, normalised, sha256(bytes), effectiveTag(file, normalised, tagOverride), version.asInt());
    }

    /** Schema validation has already limited numeric values to 0, 1, 2 or 3, including 3.0/3e0. */
    private static void normaliseOptimization(JsonNode component) {
        JsonNode options = component.path("build").path("native");
        JsonNode optimization = options.path("optimization");
        if (optimization.isNumber()) {
            ((ObjectNode) options).put("optimization", Integer.toString(optimization.intValue()));
        }
    }

    private static Object parse(Path file, byte[] bytes) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(50);
        options.setCodePointLimit(1024 * 1024);
        List<Object> documents = new ArrayList<>();
        try {
            new Yaml(new SafeConstructor(options)).loadAll(new ByteArrayInputStream(bytes)).forEach(documents::add);
        } catch (YAMLException exception) {
            throw failure(file, exception.getMessage());
        }
        if (documents.size() > 1) {
            throw failure(file, "a recipe must contain a single YAML document, found " + documents.size());
        }
        if (documents.isEmpty() || documents.getFirst() == null) {
            throw failure(file, "recipe is empty");
        }
        return documents.getFirst();
    }

    /** Converts SafeConstructor output to JSON, rejecting what JSON cannot represent (dates, binary, cycles, ...). */
    private static JsonNode toJson(Path file, Object value, StringBuilder path,
                                   IdentityHashMap<Object, Boolean> open) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (value) {
            case null -> nodes.nullNode();
            case String text -> TextNode.valueOf(text);
            case Boolean bool -> nodes.booleanNode(bool);
            case Integer number -> nodes.numberNode(number);
            case Long number -> nodes.numberNode(number);
            case BigInteger number -> nodes.numberNode(number);
            case Double number when Double.isFinite(number) -> nodes.numberNode(number);
            case Map<?, ?> map -> {
                enter(file, map, path, open);
                ObjectNode object = nodes.objectNode();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw failure(file, location(path) + ": key " + entry.getKey() + " is not a string");
                    }
                    int length = path.length();
                    path.append('.').append(key);
                    object.set(key, toJson(file, entry.getValue(), path, open));
                    path.setLength(length);
                }
                open.remove(map);
                yield object;
            }
            case List<?> list -> {
                enter(file, list, path, open);
                ArrayNode array = nodes.arrayNode();
                for (int index = 0; index < list.size(); index++) {
                    int length = path.length();
                    path.append('[').append(index).append(']');
                    array.add(toJson(file, list.get(index), path, open));
                    path.setLength(length);
                }
                open.remove(list);
                yield array;
            }
            default -> throw failure(file, location(path) + ": value " + describe(value) + " is not representable as JSON");
        };
    }

    private static void enter(Path file, Object container, CharSequence path, IdentityHashMap<Object, Boolean> open) {
        if (open.put(container, Boolean.TRUE) != null) {
            throw failure(file, location(path) + ": recursive YAML alias is not representable as JSON");
        }
    }

    private static String describe(Object value) {
        return value instanceof byte[] ? "of type binary" : "'" + value + "' of type " + value.getClass().getSimpleName();
    }

    private static String effectiveTag(Path file, JsonNode data, String tagOverride) {
        JsonNode registry = data.path("registry");
        if (tagOverride == null) {
            return registry.isMissingNode() ? LOCAL_TAG : registry.path("tag").asText();
        }
        if (registry.isMissingNode()) {
            throw failure(file, "-PrecipeTag requires a registry section in the recipe");
        }
        if (!TAG_SCHEMA.validate(TextNode.valueOf(tagOverride)).isEmpty()) {
            throw failure(file, "-PrecipeTag: '" + tagOverride + "' is not a valid container tag");
        }
        return tagOverride;
    }

    /** Turns "$.functions[1].sdk" or ".functions[1].sdk" into "functions[1].sdk". */
    private static String location(CharSequence raw) {
        String text = raw.toString();
        String trimmed = text.startsWith("$") ? text.substring(1) : text;
        trimmed = trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
        return trimmed.isEmpty() ? "(root)" : trimmed;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static GradleException failure(Path file, String message) {
        return new GradleException(file + ": " + message);
    }
}
