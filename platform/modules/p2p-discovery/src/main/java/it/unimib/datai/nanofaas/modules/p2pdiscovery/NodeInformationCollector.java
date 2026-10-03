package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import static it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformation.*;

/** Reads existing local observations; scheduling and publication decisions belong to the exchange. */
public final class NodeInformationCollector {
    public record EnvironmentMemory(long totalBytes, long freeBytes) {}
    private final FunctionCatalogView catalog;
    private final WorkloadMetricsSource workloads;
    private final ImageInventorySource images;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Supplier<EnvironmentMemory> memory;

    public NodeInformationCollector(FunctionCatalogView catalog, WorkloadMetricsSource workloads,
                                    ImageInventorySource images, MeterRegistry meters, Clock clock,
                                    Supplier<EnvironmentMemory> memory) {
        this.catalog = catalog; this.workloads = workloads; this.images = images;
        this.meters = meters; this.clock = clock; this.memory = memory;
    }

    static EnvironmentMemory visibleMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return new EnvironmentMemory(os.getTotalMemorySize(), os.getFreeMemorySize());
        }
        return new EnvironmentMemory(-1, -1);
    }

    public Category<List<FunctionInfo>> collectFunctions() {
        if (catalog == null) return Category.unavailable("NO_CATALOG");
        var registered = catalog.listRegistered();
        if (registered.size() > NodeInformationCodec.MAX_ENTRIES) return Category.unavailable("LIMIT_EXCEEDED");
        List<FunctionInfo> data = registered.stream().map(f -> new FunctionInfo(f.name(),
                f.deploymentMetadata().effectiveExecutionMode().name(), f.spec().image(),
                f.deploymentMetadata().deploymentBackend())).sorted(Comparator.comparing(FunctionInfo::name)).toList();
        return new Category<>(Status.AVAILABLE, clock.instant(), "function-catalog", "local-node", null, data, 0);
    }

    public Category<ImageInventory> collectImages(Duration timeout) {
        if (images == null) return Category.unavailable("NO_PROVIDER");
        ImageInventory inventory = images.snapshot(timeout, NodeInformationCodec.MAX_ENTRIES);
        if (inventory.status() == ImageInventory.Status.UNAVAILABLE) return Category.unavailable(inventory.reasonCode());
        return new Category<>(inventory.status() == ImageInventory.Status.PARTIAL ? Status.PARTIAL : Status.AVAILABLE,
                inventory.collectedAt(), inventory.backend(), inventory.scope(), inventory.reasonCode(), inventory, 0);
    }

    public Category<ResourceInfo> collectResources() {
        Double environmentCpu = ratio("system.cpu.usage");
        Double processCpu = ratio("process.cpu.usage");
        Long heapUsed = heap("jvm.memory.used");
        Long heapMax = heap("jvm.memory.max");
        Long total = null;
        Long used = null;
        try {
            EnvironmentMemory m = memory.get();
            if (m.totalBytes() >= 0 && m.freeBytes() >= 0 && m.freeBytes() <= m.totalBytes()) {
                total = m.totalBytes(); used = m.totalBytes() - m.freeBytes();
            }
        } catch (RuntimeException ignored) { /* unsupported observation, not a synthetic zero */ }
        List<FunctionLoad> load = List.of();
        boolean loadAvailable = catalog != null && workloads != null;
        if (loadAvailable) {
            var registered = catalog.listRegistered();
            if (registered.size() > NodeInformationCodec.MAX_ENTRIES) return Category.unavailable("LIMIT_EXCEEDED");
            load = registered.stream().map(f -> new FunctionLoad(f.name(), workloads.queueDepth(f.name()),
                    workloads.inFlight(f.name()), workloads.effectiveConcurrency(f.name()),
                    workloads.dispatchableBacklog(f.name()))).sorted(Comparator.comparing(FunctionLoad::name)).toList();
        }
        if (environmentCpu == null && processCpu == null && heapUsed == null && heapMax == null
                && total == null && !loadAvailable) return Category.unavailable("NO_MEASUREMENTS");
        boolean complete = environmentCpu != null && processCpu != null && heapUsed != null && heapMax != null
                && total != null && loadAvailable;
        return new Category<>(complete ? Status.AVAILABLE : Status.PARTIAL, clock.instant(),
                "micrometer-and-platform-management", "control-plane-visible-environment;control-plane-process;jvm-heap;nanofaas-workload",
                complete ? null : "MISSING_MEASUREMENTS",
                new ResourceInfo(environmentCpu, used, total, processCpu, heapUsed, heapMax, load), 0);
    }

    private Double ratio(String name) {
        try {
            Gauge gauge = meters.find(name).gauge();
            if (gauge == null) return null;
            double value = gauge.value();
            return Double.isFinite(value) && value >= 0 && value <= 1 ? value : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private Long heap(String name) {
        try {
            var gauges = meters.find(name).tag("area", "heap").gauges();
            if (gauges.isEmpty()) return null;
            double sum = 0;
            for (Gauge gauge : gauges) {
                double value = gauge.value();
                if (!Double.isFinite(value) || value < 0) return null;
                sum += value;
            }
            return sum <= Long.MAX_VALUE ? (long) sum : null;
        } catch (RuntimeException ignored) { return null; }
    }
}
