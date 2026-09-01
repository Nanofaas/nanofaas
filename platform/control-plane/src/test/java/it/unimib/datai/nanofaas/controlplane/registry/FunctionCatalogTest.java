package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
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

import static org.junit.jupiter.api.Assertions.*;

class FunctionCatalogTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
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
    void rejectsUnsupportedSchemaAndDuplicateOrInvalidFunctions() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, "{\"schemaVersion\":2,\"functions\":[]}");
        FunctionCatalog catalog = catalog(path);
        List<RegisteredFunction> duplicates = List.of(function("same"), function("same"));
        List<RegisteredFunction> invalid = List.of(function(""));
        assertThrows(IllegalStateException.class, () -> catalog.load());
        assertThrows(IllegalStateException.class, () -> catalog.save(duplicates));
        assertThrows(IllegalStateException.class, () -> catalog.save(invalid));
    }

    @Test
    void rejectsMalformedOrUnreadableCatalog() throws IOException {
        Path path = tempDir.resolve("functions.json");
        Files.writeString(path, "not json");
        FunctionCatalog catalog = catalog(path);
        assertThrows(IllegalStateException.class, () -> catalog.load());

        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_WRITE));
            Assumptions.assumeFalse(Files.isReadable(path));
            assertThrows(IllegalStateException.class, () -> catalog.load());
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
        assertThrows(IllegalStateException.class, () -> catalog.load());

        Files.writeString(path, """
                {"schemaVersion":1,"functions":[
                  {"spec":{"name":"","image":"example:latest"}}]}
                """);
        assertThrows(IllegalStateException.class, () -> catalog.load());
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
