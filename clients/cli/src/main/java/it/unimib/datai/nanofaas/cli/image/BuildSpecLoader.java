package it.unimib.datai.nanofaas.cli.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BuildSpecLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    private BuildSpecLoader() {}

    public static BuildSpec load(Path functionYaml) {
        JsonNode root = readTree(functionYaml);
        JsonNode build = root.path("x-cli").path("build");
        if (build.isMissingNode() || build.isNull()) {
            throw new IllegalArgumentException("Missing x-cli.build in " + functionYaml);
        }

        String contextText = text(build, "context", null);
        if (contextText == null || contextText.isBlank()) {
            throw new IllegalArgumentException("Missing x-cli.build.context in " + functionYaml);
        }

        Path context = Path.of(contextText);
        Path dockerfile = Path.of(text(build, "dockerfile", "Dockerfile"));
        String platform = text(build, "platform", null);
        boolean push = bool(build, "push", true);
        Map<String, String> buildArgs = map(build.path("buildArgs"));

        return new BuildSpec(context, dockerfile, platform, push, buildArgs);
    }

    private static JsonNode readTree(Path path) {
        try {
            return YAML.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        }
    }

    private static String text(JsonNode node, String field, String def) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return def;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? def : s;
    }

    private static boolean bool(JsonNode node, String field, boolean def) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return def;
        }
        return v.asBoolean(def);
    }

    private static Map<String, String> map(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> m = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            m.put(e.getKey(), e.getValue().asText());
        }
        return m;
    }
}
