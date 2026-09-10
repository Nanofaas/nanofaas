package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class FunctionRegistryPersistenceTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void constructorRestoresCatalogSnapshot() {
        FunctionCatalog catalog = catalog("functions.json");
        catalog.save(List.of(RegisteredFunction.nonManaged(spec("saved", ExecutionMode.LOCAL, null))));

        assertEquals("saved", new FunctionRegistry(catalog).get("saved").orElseThrow().name());
    }

    @Test
    void failedSaveDoesNotPublishMutation() {
        FunctionCatalog failingCatalog = new FunctionCatalog(new FunctionCatalogProperties(tempDir.resolve("functions.json")),
                objectMapper, validator, (source, target) -> { throw new IOException("move failed"); });
        FunctionRegistry registry = new FunctionRegistry(failingCatalog);

        FunctionSpec lost = spec("lost", ExecutionMode.LOCAL, null);
        assertThrows(IllegalStateException.class, () -> registry.put(lost));
        assertTrue(registry.get("lost").isEmpty());
    }

    @Test
    void mutationsSurviveRestartIncludingPutIfAbsentReplacementAndRemoval() {
        FunctionCatalog catalog = catalog("functions.json");
        FunctionRegistry registry = new FunctionRegistry(catalog);
        FunctionSpec first = spec("fn", ExecutionMode.LOCAL, null);
        FunctionSpec replacement = new FunctionSpec("fn", "other:latest", List.of(), java.util.Map.of(), null,
                1000, 1, 1, 0, null, ExecutionMode.LOCAL, null, null, null);

        assertNull(registry.putIfAbsent(first));
        assertEquals(first, registry.putIfAbsent(replacement));
        assertEquals(first, registry.put(replacement));
        assertEquals(replacement, new FunctionRegistry(catalog).get("fn").orElseThrow());
        assertEquals(replacement, registry.remove("fn"));
        assertTrue(new FunctionRegistry(catalog).get("fn").isEmpty());
    }

    @Test
    void managedDesiredReplicasDefaultsAndPersistsZeroWhileNonManagedIsNull() {
        FunctionCatalog catalog = catalog("functions.json");
        FunctionRegistry registry = new FunctionRegistry(catalog);
        RegisteredFunction managed = new RegisteredFunction(spec("managed", ExecutionMode.DEPLOYMENT,
                new ScalingConfig(ScalingStrategy.INTERNAL, 2, 5, List.of())),
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));

        assertEquals(2, managed.desiredReplicas());
        assertEquals(0, managed.withDesiredReplicas(0).desiredReplicas());
        assertNull(RegisteredFunction.nonManaged(spec("local", ExecutionMode.LOCAL, null)).desiredReplicas());
        registry.put(managed.withDesiredReplicas(0));
        assertEquals(0, new FunctionRegistry(catalog).listRegisteredForRecovery().iterator().next().desiredReplicas());
    }

    private FunctionCatalog catalog(String name) {
        return new FunctionCatalog(new FunctionCatalogProperties(tempDir.resolve(name)), objectMapper, validator);
    }

    private static FunctionSpec spec(String name, ExecutionMode mode, ScalingConfig scaling) {
        return new FunctionSpec(name, "example:latest", List.of(), java.util.Map.of(), null,
                1000, 1, 1, 0, null, mode, null, null, scaling, null);
    }
}
