package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import java.math.BigDecimal;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class FunctionCatalogTest {
    @TempDir
    Path tempDir;

    // Match the strict mapper configured in application.yml.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void missingCatalogLoadsEmpty() {
        assertTrue(catalog(tempDir.resolve("missing.json")).load().isEmpty());
    }

    @Test
    void savesSchemaAndLoadsFunctionsInNameOrder() throws IOException {
        Path path = tempDir.resolve("functions.json");
        FunctionCatalog catalog = catalog(path);

        catalog.save(List.of(function("zulu"), function("alpha")));

        assertEquals(List.of("alpha", "zulu"), catalog.load().stream().map(RegisteredFunction::name).toList());
        assertEquals(1, objectMapper.readTree(Files.readAllBytes(path)).get("schemaVersion").asInt());
    }

    @Test
    void explicitResourcesSurviveRegistryRestart() throws IOException {
        Path path = tempDir.resolve("functions.json");
        ResourceSpec resources = new ResourceSpec(
                new ResourceQuantity(new BigDecimal("0.05"), 128),
                new ResourceQuantity(BigDecimal.ONE, 256));
        FunctionSpec spec = new FunctionSpec("resources", "example:latest", List.of(), java.util.Map.of(), resources,
                1000, 1, 1, 0, null, ExecutionMode.LOCAL, null, null, null);
        new FunctionRegistry(catalog(path)).put(spec);

        assertEquals(spec, new FunctionRegistry(catalog(path)).get("resources").orElseThrow());
        assertFalse(objectMapper.readTree(Files.readAllBytes(path)).get("functions").get(0)
                .get("spec").get("resources").has("requestWithinLimit"));
    }

    @Test
    void loadsLegacyValidationPropertyAndRewritesOnlyResourceConfiguration() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, """
                {"schemaVersion":1,"functions":[{"spec":{"name":"legacy","image":"example:latest",
                  "resources":{"requests":{"cpu":0.05,"memoryMiB":128},
                    "limits":{"cpu":1,"memoryMiB":256},"requestWithinLimit":false}}}]}
                """);
        FunctionRegistry restored = new FunctionRegistry(catalog(path));
        ResourceSpec resources = restored.get("legacy").orElseThrow().resources();
        assertEquals(new ResourceQuantity(new BigDecimal("0.05"), 128), resources.requests());
        assertEquals(new ResourceQuantity(BigDecimal.ONE, 256), resources.limits());
        catalog(path).save(restored.listRegisteredForRecovery());
        assertFalse(objectMapper.readTree(Files.readAllBytes(path)).get("functions").get(0)
                .get("spec").get("resources").has("requestWithinLimit"));
    }

    @Test
    void legacyValidationPropertyCannotBypassResourceLimits() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, """
                {"schemaVersion":1,"functions":[{"spec":{"name":"invalid","image":"example:latest",
                  "resources":{"requests":{"cpu":2,"memoryMiB":512},
                    "limits":{"cpu":1,"memoryMiB":256},"requestWithinLimit":true}}}]}
                """);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> catalog(path).load());
        assertEquals("Function catalog contains an invalid function: invalid", failure.getMessage());
    }

    @Test
    void rejectsUnknownResourceConfiguration() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, """
                {"schemaVersion":1,"functions":[{"spec":{"name":"unknown","image":"example:latest",
                  "resources":{"requests":{"cpu":0.05,"memoryMiB":128},"limit":{}}}}]}
                """);
        assertThrows(IllegalStateException.class, () -> catalog(path).load());
    }

    @Test
    void rejectsUnsupportedSchemaAndDuplicateOrInvalidFunctions() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, "{\"schemaVersion\":2,\"functions\":[]}");
        FunctionCatalog catalog = catalog(path);
        List<RegisteredFunction> duplicates = List.of(function("same"), function("same"));
        List<RegisteredFunction> invalid = List.of(function(""));
        assertThrows(IllegalStateException.class, catalog::load);
        assertThrows(IllegalStateException.class, () -> catalog.save(duplicates));
        assertThrows(IllegalStateException.class, () -> catalog.save(invalid));
    }

    @Test
    void rejectsMalformedOrUnreadableCatalog() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, "not json");
        FunctionCatalog catalog = catalog(path);
        assertThrows(IllegalStateException.class, catalog::load);

        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_WRITE));
            Assumptions.assumeFalse(Files.isReadable(path));
            assertThrows(IllegalStateException.class, catalog::load);
        }
    }

    @Test
    void rejectsDuplicateOrInvalidFunctionsWhenLoading() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, """
                {"schemaVersion":1,"functions":[
                  {"spec":{"name":"same","image":"example:latest"}},
                  {"spec":{"name":"same","image":"example:latest"}}]}
                """);
        FunctionCatalog catalog = catalog(path);
        assertThrows(IllegalStateException.class, catalog::load);

        Files.writeString(path, """
                {"schemaVersion":1,"functions":[
                  {"spec":{"name":"","image":"example:latest"}}]}
                """);
        assertThrows(IllegalStateException.class, catalog::load);
    }

    @Test
    void moveFailureCleansTempFileAndPreservesOldCatalog() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, "old catalog");
        FunctionCatalog catalog = new FunctionCatalog(new FunctionCatalogProperties(path), objectMapper, validator,
                (source, target) -> { throw new IOException("move failed"); });

        List<RegisteredFunction> functions = List.of(function("fn"));
        assertThrows(IllegalStateException.class, () -> catalog.save(functions));
        assertEquals("old catalog", Files.readString(path));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test
    void savesPrivatePermissionsWhenPosixIsAvailable() throws IOException {
        Path path = tempDir.resolve("private/functions.json");
        catalog(path).save(List.of(function("fn")));

        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(path.getParent()));
        }
    }

    private FunctionCatalog catalog(Path path) {
        return new FunctionCatalog(new FunctionCatalogProperties(path), objectMapper, validator);
    }

    private static RegisteredFunction function(String name) {
        return RegisteredFunction.nonManaged(new FunctionSpec(name, "example:latest", List.of(), java.util.Map.of(), null,
                1000, 1, 1, 0, null, ExecutionMode.LOCAL, null, null, null));
    }
}
