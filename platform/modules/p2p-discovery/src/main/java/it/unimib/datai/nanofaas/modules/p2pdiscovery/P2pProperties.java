package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param enabled      master switch; the module is inert when false
 * @param nodeId       stable id of this node; generated and stored in the state file when absent
 * @param port         cluster transport port (0 = ephemeral)
 * @param externalHost address advertised to peers (containers/NAT); null = auto
 * @param seeds        host:port of nodes to join through
 * @param maxNeighbors max active neighbors; null = unlimited
 * @param maxLatencyMs latency threshold; null = filter not applied
 * @param stateFile    YAML file with operator {@code config} and node {@code state}; null = none
 */
@ConfigurationProperties(prefix = "nanofaas.p2p")
public record P2pProperties(
        Boolean enabled,
        String nodeId,
        Integer port,
        String externalHost,
        List<String> seeds,
        Integer maxNeighbors,
        Double maxLatencyMs,
        Duration pingInterval,
        Duration pingTimeout,
        String stateFile,
        Admin admin
) {
    public record Admin(Boolean enabled) {
        public Admin {
            if (enabled == null) {
                enabled = false;
            }
        }
    }

    public P2pProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (port == null) {
            port = 0;
        }
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
        if (pingInterval == null) {
            pingInterval = Duration.ofSeconds(1);
        }
        if (pingTimeout == null) {
            pingTimeout = Duration.ofSeconds(2);
        }
        if (admin == null) {
            admin = new Admin(null);
        }
    }
}
