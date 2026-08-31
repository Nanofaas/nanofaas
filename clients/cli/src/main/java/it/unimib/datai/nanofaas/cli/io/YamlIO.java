package it.unimib.datai.nanofaas.cli.io;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class YamlIO {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectMapper YAML_STRICT = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules();

    private static final ObjectMapper YAML_TREE = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
                    .build())
            .findAndRegisterModules();

    private YamlIO() {}

    public static <T> T read(Path path, Class<T> type) {
        try {
            return YAML.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        }
    }

    public static <T> T readStrict(Path path, Class<T> type) {
        try {
            return YAML_STRICT.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        }
    }

    public static JsonNode readTree(Path path) {
        try {
            return YAML_TREE.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        }
    }
}
