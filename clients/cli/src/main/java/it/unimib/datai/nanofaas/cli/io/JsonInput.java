package it.unimib.datai.nanofaas.cli.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonInput {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private JsonInput() {}

    public static JsonNode read(String data) {
        return read(data, System.in);
    }

    public static JsonNode read(String data, InputStream stdin) {
        String raw = data;
        if (data.startsWith("@")) {
            String ref = data.substring(1);
            try {
                raw = ref.equals("-")
                        ? new String(stdin.readAllBytes(), StandardCharsets.UTF_8)
                        : Files.readString(Path.of(ref));
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read input: " + data, e);
            }
        }
        try {
            return JSON.readTree(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON input", e);
        }
    }
}
