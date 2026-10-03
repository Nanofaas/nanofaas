package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformation.*;

class NodeInformationCollectorTest {
    @Test void catalogChangesAndWorkloadUseRealSourcesWithoutExposingEnvironmentOrEndpoints() {
        var registered = new java.util.concurrent.CopyOnWriteArrayList<it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction>();
        var spec = new it.unimib.datai.nanofaas.common.model.FunctionSpec("fn", "example:latest", List.of(),
                java.util.Map.of("SECRET", "hidden"), null, 1000, 1, 1, 0, null,
                it.unimib.datai.nanofaas.common.model.ExecutionMode.LOCAL, null, null, null, null);
        var workloads = org.mockito.Mockito.mock(it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource.class);
        org.mockito.Mockito.when(workloads.queueDepth("fn")).thenReturn(7);
        org.mockito.Mockito.when(workloads.inFlight("fn")).thenReturn(2);
        org.mockito.Mockito.when(workloads.effectiveConcurrency("fn")).thenReturn(3);
        org.mockito.Mockito.when(workloads.dispatchableBacklog("fn")).thenReturn(5);
        var c = new NodeInformationCollector(() -> registered, workloads, null, new SimpleMeterRegistry(),
                Clock.systemUTC(), () -> new NodeInformationCollector.EnvironmentMemory(-1, -1));
        registered.add(it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction.nonManaged(spec));
        assertThat(c.collectFunctions().data()).containsExactly(new FunctionInfo("fn", "LOCAL", "example:latest", null));
        assertThat(c.collectResources().data().functions()).containsExactly(new FunctionLoad("fn", 7, 2, 3, 5));
        registered.clear();
        assertThat(c.collectFunctions().data()).isEmpty();
        assertThat(c.collectResources().data().functions()).isEmpty();
    }

    @Test void missingSourcesAreNotEmptySuccesses() {
        var c = new NodeInformationCollector(null, null, null, new SimpleMeterRegistry(), Clock.systemUTC(),
                () -> new NodeInformationCollector.EnvironmentMemory(100, 25));
        assertThat(c.collectImages(Duration.ofSeconds(2)).reasonCode()).isEqualTo("NO_PROVIDER");
        assertThat(c.collectFunctions().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(c.collectResources().data().environmentMemoryUsedBytes()).isEqualTo(75);
        assertThat(c.collectResources().status()).isEqualTo(Status.PARTIAL);
    }

    @Test void actualZerosArePreservedAndUnsupportedGaugesRemainNull() {
        var meters = new SimpleMeterRegistry();
        Gauge.builder("system.cpu.usage", () -> 0).register(meters);
        Gauge.builder("process.cpu.usage", () -> Double.NaN).register(meters);
        var c = new NodeInformationCollector(List::of, null, null, meters, Clock.systemUTC(),
                () -> new NodeInformationCollector.EnvironmentMemory(100, 100));
        assertThat(c.collectFunctions().status()).isEqualTo(Status.AVAILABLE);
        assertThat(c.collectFunctions().data()).isEmpty();
        ResourceInfo r = c.collectResources().data();
        assertThat(r.environmentCpuRatio()).isZero();
        assertThat(r.environmentMemoryUsedBytes()).isZero();
        assertThat(r.processCpuRatio()).isNull();
        assertThat(r.jvmHeapUsedBytes()).isNull();
    }
}
