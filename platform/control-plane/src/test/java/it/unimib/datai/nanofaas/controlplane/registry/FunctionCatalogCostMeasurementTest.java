package it.unimib.datai.nanofaas.controlplane.registry;

import com.sun.management.ThreadMXBean;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import jakarta.validation.Validation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FunctionCatalogCostMeasurementTest {

    private static final List<Integer> CATALOG_SIZES = List.of(1, 100, 1_000);
    private static final long CONTROL_LOOP_NANOS = Duration.ofSeconds(5).toNanos();

    @TempDir
    Path tempDir;

    @Test
    void measuresSnapshotMutationCostsAtDeclaredCatalogSizes() throws Exception {
        ThreadMXBean allocations = allocationBean();
        warmUpMeasurementPaths();
        for (int size : CATALOG_SIZES) {
            assertMeasurement(measure("register", size, false, registry ->
                    () -> registry.put(nonManaged("new-function", 1_000))), allocations);
            assertMeasurement(measure("update", size, false, registry ->
                    () -> registry.put(nonManaged("fn-0", 2_000))), allocations);
            assertMeasurement(measure("remove", size, false, registry ->
                    () -> registry.removeRegistered("fn-0")), allocations);
            assertMeasurement(measure("desired-replicas", size, true, registry -> {
                ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
                when(provider.backendId()).thenReturn("k8s");
                ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
                        new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                        registry,
                        new FunctionOperationLocks());
                return () -> coordinator.setReplicas(new ManagedDeploymentTarget("fn-0", "k8s"), 2);
            }), allocations);
        }
    }

    private void warmUpMeasurementPaths() throws Exception {
        measure("warm-register", 10, false, registry ->
                () -> registry.put(nonManaged("new-function", 1_000)));
        measure("warm-update", 10, false, registry ->
                () -> registry.put(nonManaged("fn-0", 2_000)));
        measure("warm-remove", 10, false, registry ->
                () -> registry.removeRegistered("fn-0"));
        measure("warm-desired", 10, true, registry -> {
            ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
            when(provider.backendId()).thenReturn("k8s");
            ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
                    new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                    registry,
                    new FunctionOperationLocks());
            return () -> coordinator.setReplicas(new ManagedDeploymentTarget("fn-0", "k8s"), 2);
        });
    }

    private Measurement measure(String operation,
                                int size,
                                boolean managed,
                                RegistryMutationFactory mutationFactory) throws Exception {
        Path path = tempDir.resolve(operation + "-" + size + ".json");
        MeasuringCatalog catalog = new MeasuringCatalog(path);
        List<RegisteredFunction> initial = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            initial.add(managed ? managed("fn-" + index, 1) : nonManaged("fn-" + index, 1_000));
        }
        catalog.save(initial);
        catalog.resetWrites();
        FunctionRegistry registry = new FunctionRegistry(catalog);
        if (managed) {
            registry.replaceAllAfterRestore(initial, initial);
            catalog.resetWrites();
        }
        Runnable mutation = mutationFactory.prepare(registry);
        ThreadMXBean allocations = allocationBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocations.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();

        mutation.run();

        long durationNanos = System.nanoTime() - started;
        long allocatedBytes = allocations.getThreadAllocatedBytes(threadId) - allocatedBefore;
        return new Measurement(operation, size, allocatedBytes, Files.size(path), durationNanos, catalog.writes());
    }

    private static void assertMeasurement(Measurement measurement, ThreadMXBean allocations) {
        assertThat(allocations.isThreadAllocatedMemoryEnabled()).isTrue();
        assertThat(measurement.allocatedBytes()).isPositive();
        assertThat(measurement.serializedBytes()).isPositive();
        assertThat(measurement.durationNanos()).isPositive().isLessThan(CONTROL_LOOP_NANOS);
        assertThat(measurement.writes()).isEqualTo(1);
        System.out.printf("P15_MEASUREMENT operation=%s size=%d allocatedBytes=%d serializedBytes=%d "
                        + "durationNanos=%d writes=%d%n",
                measurement.operation(), measurement.size(), measurement.allocatedBytes(),
                measurement.serializedBytes(), measurement.durationNanos(), measurement.writes());
    }

    private static ThreadMXBean allocationBean() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return bean;
    }

    private static RegisteredFunction nonManaged(String name, int timeoutMs) {
        return RegisteredFunction.nonManaged(spec(name, timeoutMs, ExecutionMode.LOCAL));
    }

    private static RegisteredFunction managed(String name, int desiredReplicas) {
        return new RegisteredFunction(
                spec(name, 1_000, ExecutionMode.DEPLOYMENT),
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null,
                        "http://" + name, Map.of("deployment", name), desiredReplicas));
    }

    private static FunctionSpec spec(String name, int timeoutMs, ExecutionMode mode) {
        return new FunctionSpec(name, "example:latest", List.of(), Map.of(), null,
                timeoutMs, 1, 1, 0, null, mode, null, null, null, null);
    }

    private record Measurement(
            String operation,
            int size,
            long allocatedBytes,
            long serializedBytes,
            long durationNanos,
            int writes
    ) {
    }

    @FunctionalInterface
    private interface RegistryMutationFactory {
        Runnable prepare(FunctionRegistry registry);
    }

    private static final class MeasuringCatalog extends FunctionCatalog {
        private int writes;

        private MeasuringCatalog(Path path) {
            super(new FunctionCatalogProperties(path), new ObjectMapper(),
                    Validation.buildDefaultValidatorFactory().getValidator());
        }

        @Override
        public void save(Collection<RegisteredFunction> functions) {
            super.save(functions);
            writes++;
        }

        private int writes() {
            return writes;
        }

        private void resetWrites() {
            writes = 0;
        }
    }
}
