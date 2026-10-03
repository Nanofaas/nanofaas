package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class NodeInformationThreeNodeTest {
    @Test void exchangesRealImagesWithReceiveOnlyNodeAndMasksAtRuntime() {
        var services = new ArrayList<P2pService>();
        var settings = new ArrayList<P2pSettings>();
        var catalogs = new ArrayList<java.util.concurrent.CopyOnWriteArrayList<it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction>>();
        try {
            for (int index = 0; index < 3; index++) {
                String id = "information-" + index;
                var props = new P2pProperties(true, id, 0, "127.0.0.1",
                        index == 0 ? List.of() : List.of(services.getFirst().address()), null, null,
                        Duration.ofMillis(200), Duration.ofSeconds(1), null, null,
                        index != 0, index != 0, index != 0);
                var config = new P2pSettings(null, null);
                var table = new PeerTable(config::effective);
                var meters = new SimpleMeterRegistry();
                var catalog = new java.util.concurrent.CopyOnWriteArrayList<it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction>();
                String functionName = "fn-" + id;
                var spec = new it.unimib.datai.nanofaas.common.model.FunctionSpec(functionName, "actual:" + id,
                        List.of(), Map.of("SECRET", "not-published"), null, 1000, 1, 1, 0, null,
                        it.unimib.datai.nanofaas.common.model.ExecutionMode.LOCAL, null, null, null, null);
                catalog.add(it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction.nonManaged(spec));
                catalogs.add(catalog);
                var workload = org.mockito.Mockito.mock(it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource.class);
                org.mockito.Mockito.when(workload.queueDepth(functionName)).thenReturn(index + 3);
                org.mockito.Mockito.when(workload.effectiveConcurrency(functionName)).thenReturn(index + 1);
                var collector = new NodeInformationCollector(() -> catalog, workload,
                        (timeout, max) -> new ImageInventory("test-engine", "local-engine", Instant.now(),
                                ImageInventory.Status.AVAILABLE, null,
                                List.of(new ImageInventory.Entry(null, List.of("actual:" + id), null, "sha256:" + id))),
                        meters, Clock.systemUTC(), () -> new NodeInformationCollector.EnvironmentMemory(100, 40));
                var node = new P2pService(props, table, config, meters, collector);
                services.add(node); settings.add(config); node.start();
            }
            var a = services.getFirst();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                for (String id : List.of("information-1", "information-2")) {
                    var info = a.peerInformation(id).orElseThrow().information();
                    assertThat(info).isNotNull();
                    assertThat(info.images().data().entries().getFirst().references()).containsExactly("actual:" + id);
                    assertThat(info.functions().data()).extracting(NodeInformation.FunctionInfo::name)
                            .containsExactly("fn-" + id);
                    assertThat(info.resources().data().functions()).extracting(NodeInformation.FunctionLoad::name)
                            .containsExactly("fn-" + id);
                    assertThat(info.resources().data().environmentMemoryUsedBytes()).isEqualTo(60);
                }
            });
            assertThat(a.localInformation().images().status()).isEqualTo(NodeInformation.Status.DISABLED);
            var b = services.get(1);
            assertThat(a.peerInformation("information-1").orElseThrow().information()
                    .resources().data().functions().getFirst().queueDepth()).isEqualTo(4);
            catalogs.get(1).clear();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(a.peerInformation("information-1").orElseThrow().information().functions().data()).isEmpty());
            settings.get(1).patch(Map.of("shareImages", false));
            b.informationSettingsChanged();
            assertThat(b.localInformation().images().data()).isNull();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(a.peerInformation("information-1").orElseThrow().information().images().status())
                            .isEqualTo(NodeInformation.Status.DISABLED));
            a.tableForTest().setApiMode("information-1", PeerMode.EXCLUDED);
            assertThat(a.peerInformation("information-1").orElseThrow().state()).isEqualTo("INACTIVE");
            a.setState(P2pService.State.ISOLATED);
            assertThat(a.peerInformation("information-2").orElseThrow().information()).isNull();
            a.setState(P2pService.State.ACTIVE);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(a.peerInformation("information-2").orElseThrow().state()).isEqualTo("CURRENT"));
        } finally { services.forEach(P2pService::stop); }
    }
}
