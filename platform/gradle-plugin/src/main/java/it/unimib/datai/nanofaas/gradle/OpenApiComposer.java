package it.unimib.datai.nanofaas.gradle;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merges the core OpenAPI document with per-module fragments into one deterministic YAML file.
 * Fragments contribute new paths/components and may patch existing operations via
 * {@code x-nanofaas-overlays}: a list of {@code {operationId, patch}} entries, applied with
 * JSON-merge-patch semantics (maps recurse, null removes, anything else replaces).
 */
final class OpenApiComposer {

    private static final List<String> ALLOWED_FRAGMENT_KEYS = List.of("paths", "components", "x-nanofaas-overlays");

    private OpenApiComposer() {
    }

    record Write(String module, Object value) {
    }

    static void compose(Path core, Map<String, Path> fragments, Path output) {
        Map<String, Object> document = loadMap(core);
        Map<String, Object> paths = asMap(document.computeIfAbsent("paths", key -> new LinkedHashMap<>()));
        Map<String, Object> components = asMap(document.computeIfAbsent("components", key -> new LinkedHashMap<>()));

        List<String> moduleIds = new ArrayList<>(fragments.keySet());
        moduleIds.sort(String::compareTo);

        for (String moduleId : moduleIds) {
            Map<String, Object> fragment = loadMap(fragments.get(moduleId));
            for (String key : fragment.keySet()) {
                if (!ALLOWED_FRAGMENT_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            "Module '" + moduleId + "' fragment has forbidden top-level key '" + key + "'");
                }
            }
            mergePaths(paths, asMap(fragment.get("paths")), moduleId);
            mergeComponents(components, asMap(fragment.get("components")), moduleId);
        }

        Map<String, Map<String, Object>> operationsById = indexOperationsById(paths);
        Map<String, Write> writes = new LinkedHashMap<>();
        for (String moduleId : moduleIds) {
            Map<String, Object> fragment = loadMap(fragments.get(moduleId));
            for (Object overlayEntry : asList(fragment.get("x-nanofaas-overlays"))) {
                Map<String, Object> overlay = asMap(overlayEntry);
                Object operationId = overlay.get("operationId");
                Object patch = overlay.get("patch");
                if (!(operationId instanceof String id) || patch == null) {
                    throw new IllegalArgumentException("Module '" + moduleId
                            + "' has an x-nanofaas-overlays entry missing 'operationId' or 'patch'");
                }
                Map<String, Object> operation = operationsById.get(id);
                if (operation == null) {
                    throw new IllegalArgumentException(
                            "Module '" + moduleId + "' overlay targets unknown operationId '" + id + "'");
                }
                applyPatch(operation, patch, id, "", moduleId, writes);
            }
        }

        writeYaml(document, output);
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Expected x-nanofaas-overlays to be a list but found " + value.getClass());
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    private static void mergePaths(Map<String, Object> target, Map<String, Object> incoming, String moduleId) {
        for (Map.Entry<String, Object> pathEntry : incoming.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> methods = asMap(pathEntry.getValue());
            Map<String, Object> targetMethods = asMap(target.computeIfAbsent(path, key -> new LinkedHashMap<>()));
            for (Map.Entry<String, Object> methodEntry : methods.entrySet()) {
                String method = methodEntry.getKey();
                if (targetMethods.containsKey(method)) {
                    throw new IllegalArgumentException(
                            "Module '" + moduleId + "' duplicates path '" + method + " " + path + "'");
                }
                targetMethods.put(method, methodEntry.getValue());
            }
        }
    }

    private static void mergeComponents(Map<String, Object> target, Map<String, Object> incoming, String moduleId) {
        for (Map.Entry<String, Object> categoryEntry : incoming.entrySet()) {
            String category = categoryEntry.getKey();
            Map<String, Object> names = asMap(categoryEntry.getValue());
            Map<String, Object> targetNames = asMap(target.computeIfAbsent(category, key -> new LinkedHashMap<>()));
            for (Map.Entry<String, Object> nameEntry : names.entrySet()) {
                String name = nameEntry.getKey();
                if (targetNames.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "Module '" + moduleId + "' duplicates component '" + category + "/" + name + "'");
                }
                targetNames.put(name, nameEntry.getValue());
            }
        }
    }

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static Map<String, Map<String, Object>> indexOperationsById(Map<String, Object> paths) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Object pathValue : paths.values()) {
            for (Map.Entry<String, Object> methodEntry : asMap(pathValue).entrySet()) {
                if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                    continue; // path-item fields like 'parameters' or 'summary' are not operations
                }
                Map<String, Object> operation = asMap(methodEntry.getValue());
                Object operationId = operation.get("operationId");
                if (operationId instanceof String id && index.put(id, operation) != null) {
                    throw new IllegalArgumentException("Duplicate operationId '" + id + "'");
                }
            }
        }
        return index;
    }

    private static void applyPatch(Map<String, Object> target, Object patch, String operationId, String pointer,
            String moduleId, Map<String, Write> writes) {
        for (Map.Entry<String, Object> entry : asMap(patch).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String childPointer = pointer.isEmpty() ? key : pointer + "/" + key;
            if (value instanceof Map) {
                Map<String, Object> childTarget = asMap(target.computeIfAbsent(key, k -> new LinkedHashMap<>()));
                applyPatch(childTarget, value, operationId, childPointer, moduleId, writes);
            } else {
                recordWrite(operationId, childPointer, moduleId, value, writes);
                if (value == null) {
                    target.remove(key);
                } else {
                    target.put(key, value);
                }
            }
        }
    }

    // ponytail: conflict detection is per leaf JSON pointer, not subtree-aware. A module
    // removing a node ('429': null) and another module writing inside that node's subtree
    // record different pointers, so no conflict is raised and resolution silently falls back
    // to module-id sort order. Today's fragments are fully disjoint so this is unreachable;
    // revisit only if a future overlay actually needs to remove a node another module writes into.
    private static void recordWrite(String operationId, String pointer, String moduleId, Object value,
            Map<String, Write> writes) {
        String writeKey = operationId + "#" + pointer;
        Write existing = writes.putIfAbsent(writeKey, new Write(moduleId, value));
        if (existing != null && !Objects.equals(existing.value(), value)) {
            throw new IllegalArgumentException("Conflicting overlay for operation '" + operationId + "' at '"
                    + pointer + "': module '" + existing.module() + "' and module '" + moduleId + "' disagree");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a mapping but found " + value.getClass());
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMap(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            return loaded == null ? new LinkedHashMap<>() : (Map<String, Object>) loaded;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read OpenAPI fragment " + path, exception);
        }
    }

    private static void writeYaml(Map<String, Object> document, Path output) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(output)) {
                yaml.dump(document, writer);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot write composed OpenAPI to " + output, exception);
        }
    }
}
