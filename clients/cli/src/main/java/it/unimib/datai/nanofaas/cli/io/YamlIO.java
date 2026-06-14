package it.unimib.datai.nanofaas.cli.io;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class YamlIO {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private YamlIO() {}

    public static <T> T read(Path path, Class<T> type) {
        try {
            return YAML.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        }
    }
}
