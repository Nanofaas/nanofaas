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
                          ObjectProvider<MeterRegistry> meters) {
        return new P2pService(props, table, settings, meters.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
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
