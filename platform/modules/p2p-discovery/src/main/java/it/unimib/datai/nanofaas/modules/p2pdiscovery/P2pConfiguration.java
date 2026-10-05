package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@EnableConfigurationProperties(P2pProperties.class)
public class P2pConfiguration {

    @Bean
    P2pSettings p2pSettings(P2pProperties props) {
        return new P2pSettings(props.maxNeighbors(), props.maxLatencyMs(),
                new P2pSettings.Sharing(props.shareFunctions(), props.shareImages(), props.shareResources()));
    }

    @Bean
    PeerTable peerTable(P2pSettings settings) {
        return new PeerTable(settings::effective);
    }

    @Bean
    P2pService p2pService(P2pProperties props, PeerTable table, P2pSettings settings,
                          ObjectProvider<MeterRegistry> meters,
                          ObjectProvider<it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView> catalog,
                          ObjectProvider<it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource> workloads,
                          ObjectProvider<it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource> images) {
        MeterRegistry registry = meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        var collector = new NodeInformationCollector(catalog.getIfAvailable(), workloads.getIfAvailable(),
                images.getIfAvailable(), registry, java.time.Clock.systemUTC(), NodeInformationCollector::visibleMemory);
        return new P2pService(props, table, settings, registry, collector);
    }

    @Bean
    DefaultPeerTransport peerTransport(PeerTable table, P2pService service,
            @org.springframework.beans.factory.annotation.Value("${nanofaas.p2p.invocation-uri:}") String uri,
            @org.springframework.beans.factory.annotation.Value("${nanofaas.p2p.max-concurrent-requests:4}") int concurrency) {
        return new DefaultPeerTransport(table, service::transportSession,
                uri.isBlank() ? null : java.net.URI.create(uri), concurrency);
    }

    @Bean
    P2pAdminGate p2pAdminGate(P2pProperties props) {
        return new P2pAdminGate(props);
    }

    @Bean
    P2pAdminController p2pAdminController(PeerTable table, P2pSettings settings, P2pService service) {
        return new P2pAdminController(table, settings, service);
    }
}
