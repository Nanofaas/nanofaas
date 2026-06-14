package it.unimib.datai.nanofaas.modules.k8s.e2e;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
record K8sE2eScenarioManifest(
        String name,
        String baseScenario,
        String runtime,
        String namespace,
        List<SelectedFunction> functions,
        Map<String, String> payloads,
        Load load) {

    static final String SYSTEM_PROPERTY_NAME = "nanofaas.e2e.scenarioManifest";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    K8sE2eScenarioManifest {
        functions = functions == null ? List.of() : List.copyOf(functions);
        payloads = payloads == null ? Map.of() : Map.copyOf(payloads);
        load = load == null ? new Load(null, List.of()) : load;
    }

    static Optional<K8sE2eScenarioManifest> loadFromSystemProperty() {
        String manifestPath = System.getProperty(SYSTEM_PROPERTY_NAME);
        if (manifestPath == null || manifestPath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(read(Path.of(manifestPath)));
    }

    static K8sE2eScenarioManifest read(Path path) {
        try {
            return OBJECT_MAPPER.readValue(path.toFile(), K8sE2eScenarioManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read scenario manifest: " + path, e);
        }
    }

    static String systemPropertyArgument(String path) {
        return "-D" + SYSTEM_PROPERTY_NAME + "=" + path;
    }

    List<SelectedFunction> selectedFunctions() {
        return functions;
    }

    List<String> loadTargets() {
        return load.targets();
    }

    String namespaceOr(String fallback) {
        if (namespace == null || namespace.isBlank()) {
            return fallback;
        }
        return namespace;
    }

    Optional<String> payloadPathFor(String functionKey) {
        return Optional.ofNullable(payloads.get(functionKey));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SelectedFunction(
            String key,
            String family,
            String runtime,
            String image,
            String payloadPath,
            String repoRelativePayloadPath) {

        Optional<String> resolvedPayloadPath(Map<String, String> payloads) {
            if (payloadPath != null && !payloadPath.isBlank()) {
                return Optional.of(payloadPath);
            }
            return Optional.ofNullable(payloads.get(key));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Load(String profile, List<String> targets) {
        Load {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }
}
