package it.unimib.datai.nanofaas.gradle;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class ModuleDescriptorReader {

    private static final Set<String> KNOWN_PROPERTIES = Set.of(
            "schemaVersion", "id", "defaultEnabled", "requires.strong", "requires.weak", "conflicts");

    public ModuleDescriptor read(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read module descriptor " + path, exception);
        }

        for (String property : properties.stringPropertyNames()) {
            if (!KNOWN_PROPERTIES.contains(property)) {
                throw invalid("unknown property '" + property + "'");
            }
        }

        int schemaVersion = requiredInteger(properties, "schemaVersion");
        if (schemaVersion != 1) {
            throw invalid("unsupported schemaVersion " + schemaVersion);
        }
        String id = required(properties, "id");
        boolean defaultEnabled = requiredBoolean(properties, "defaultEnabled");

        return new ModuleDescriptor(
                schemaVersion,
                id,
                defaultEnabled,
                csv(properties.getProperty("requires.strong", ""), "requires.strong"),
                csv(properties.getProperty("requires.weak", ""), "requires.weak"),
                csv(properties.getProperty("conflicts", ""), "conflicts"));
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw invalid("missing or blank " + name);
        }
        return value.trim();
    }

    private static int requiredInteger(Properties properties, String name) {
        String value = required(properties, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid("invalid " + name + " '" + value + "'");
        }
    }

    private static boolean requiredBoolean(Properties properties, String name) {
        String value = required(properties, name);
        if (!value.equals("true") && !value.equals("false")) {
            throw invalid("invalid " + name + " '" + value + "'");
        }
        return Boolean.parseBoolean(value);
    }

    private static List<String> csv(String value, String name) {
        if (value.trim().isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String item : value.split(",", -1)) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!seen.add(trimmed)) {
                throw invalid("duplicate value '" + trimmed + "' in " + name);
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid module descriptor: " + message);
    }
}
