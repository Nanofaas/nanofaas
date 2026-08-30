package it.unimib.datai.nanofaas.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiComposerTest {

    @TempDir
    Path tempDir;

    private final AtomicInteger fileCounter = new AtomicInteger();

    @Test
    void addsModulePathsAndComponents() {
        Path result = compose(core(), Map.of("extra", fragmentWithExtraRoute()));

        assertThat(paths(yaml(result))).containsKey("/extra");
        assertThat(schemas(yaml(result))).containsKeys("Core", "Extra");
    }

    @Test
    void appliesDisjointOperationOverlays() {
        Path result = compose(coreWithInvoke(), Map.of(
                "queue", overlay("invokeFunctionSync", "429", "queue full"),
                "offload", overlay("invokeFunctionSync", "502", "remote failed")));

        assertThat(operationResponses(yaml(result), "invokeFunctionSync"))
                .containsKeys("200", "429", "502");
    }

    @Test
    void nullInAnOverlayRemovesAField() {
        Path result = compose(coreWithEnqueue501(), Map.of(
                "async-queue", overlayRemoving501AndAdding202()));

        assertThat(operationResponses(yaml(result), "invokeFunctionAsync"))
                .containsKey("202").doesNotContainKey("501");
    }

    @Test
    void rejectsConflictingOverlayLeaves() {
        assertThatThrownBy(() -> compose(coreWithInvoke(), Map.of(
                "one", overlay("invokeFunctionSync", "429", "first"),
                "two", overlay("invokeFunctionSync", "429", "second"))))
                .hasMessageContaining("invokeFunctionSync")
                .hasMessageContaining("one")
                .hasMessageContaining("two")
                .hasMessageContaining("responses/429/description");
    }

    @Test
    void allowsIdenticalRepeatedOverlayValues() {
        Path result = compose(coreWithInvoke(), Map.of(
                "one", overlay("invokeFunctionSync", "429", "queue full"),
                "two", overlay("invokeFunctionSync", "429", "queue full")));

        assertThat(operationResponses(yaml(result), "invokeFunctionSync")).containsKey("429");
    }

    @Test
    void rejectsDuplicateMethodAndPath() {
        assertThatThrownBy(() -> compose(core(), Map.of(
                "one", fragmentWithExtraRoute(),
                "two", fragmentWithExtraRoute())))
                .hasMessageContaining("two")
                .hasMessageContaining("/extra");
    }

    @Test
    void rejectsDuplicateComponentNames() {
        assertThatThrownBy(() -> compose(core(), Map.of("dup", write("""
                components:
                  schemas:
                    Core:
                      type: object
                """))))
                .hasMessageContaining("dup")
                .hasMessageContaining("Core");
    }

    @Test
    void rejectsUnknownOverlayTargets() {
        assertThatThrownBy(() -> compose(core(), Map.of("ghost", overlay("doesNotExist", "429", "nope"))))
                .hasMessageContaining("ghost")
                .hasMessageContaining("doesNotExist");
    }

    @Test
    void rejectsForbiddenFragmentTopLevelKeys() {
        assertThatThrownBy(() -> compose(core(), Map.of("rogue", write("""
                info:
                  title: not allowed
                """))))
                .hasMessageContaining("rogue")
                .hasMessageContaining("info");
    }

    // --- fixtures ---

    private Path core() {
        return write("""
                openapi: 3.0.3
                info:
                  title: Core
                  version: "1"
                paths: {}
                components:
                  schemas:
                    Core:
                      type: object
                """);
    }

    private Path coreWithInvoke() {
        return write("""
                openapi: 3.0.3
                info:
                  title: Core
                  version: "1"
                paths:
                  /v1/functions/{name}:invoke:
                    post:
                      operationId: invokeFunctionSync
                      responses:
                        "200":
                          description: ok
                components: {}
                """);
    }

    private Path coreWithEnqueue501() {
        return write("""
                openapi: 3.0.3
                info:
                  title: Core
                  version: "1"
                paths:
                  /v1/functions/{name}:enqueue:
                    post:
                      operationId: invokeFunctionAsync
                      responses:
                        "200":
                          description: ok
                        "501":
                          description: not implemented
                components: {}
                """);
    }

    private Path fragmentWithExtraRoute() {
        return write("""
                paths:
                  /extra:
                    get:
                      operationId: extraRoute
                      responses:
                        "200":
                          description: ok
                components:
                  schemas:
                    Extra:
                      type: object
                """);
    }

    private Path overlay(String operationId, String status, String description) {
        return write("""
                x-nanofaas-overlays:
                  %s:
                    responses:
                      "%s":
                        description: "%s"
                """.formatted(operationId, status, description));
    }

    private Path overlayRemoving501AndAdding202() {
        return write("""
                x-nanofaas-overlays:
                  invokeFunctionAsync:
                    responses:
                      "501": null
                      "202":
                        description: accepted
                """);
    }

    private Path write(String content) {
        try {
            Path file = Files.createFile(tempDir.resolve("fragment-" + fileCounter.incrementAndGet() + ".yaml"));
            Files.writeString(file, content);
            return file;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private Path compose(Path core, Map<String, Path> fragments) {
        Path output = tempDir.resolve("composed-" + fileCounter.incrementAndGet() + ".yaml");
        OpenApiComposer.compose(core, fragments, output);
        return output;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            return (Map<String, Object>) new Yaml().load(reader);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths(Map<String, Object> document) {
        return (Map<String, Object>) document.getOrDefault("paths", new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas(Map<String, Object> document) {
        Map<String, Object> components = (Map<String, Object>) document.getOrDefault("components", Map.of());
        return (Map<String, Object>) components.getOrDefault("schemas", Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> operationResponses(Map<String, Object> document, String operationId) {
        for (Object pathValue : paths(document).values()) {
            for (Object methodValue : ((Map<String, Object>) pathValue).values()) {
                Map<String, Object> operation = (Map<String, Object>) methodValue;
                if (operationId.equals(operation.get("operationId"))) {
                    return (Map<String, Object>) operation.getOrDefault("responses", Map.of());
                }
            }
        }
        throw new AssertionError("No operation with id " + operationId);
    }
}
