package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class P2pInformationRuntimeHintsTest {
    @Test void metadataIncludesAllWireAndHttpInformationRecords() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/META-INF/native-image/it.unimib.datai.nanofaas/p2p-discovery/reachability-metadata.json")) {
            assertThat(input).isNotNull();
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (Class<?> type : List.of(NodeInformation.class, NodeInformation.Category.class,
                    NodeInformation.Status.class, NodeInformation.FunctionInfo.class,
                    NodeInformation.ResourceInfo.class, NodeInformation.FunctionLoad.class,
                    NodeInformationExchange.RemoteInformation.class,
                    it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory.class,
                    it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory.Entry.class,
                    it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory.Status.class)) {
                assertThat(metadata).contains("\"type\": \"" + type.getName() + "\"");
            }
        }
    }
}
