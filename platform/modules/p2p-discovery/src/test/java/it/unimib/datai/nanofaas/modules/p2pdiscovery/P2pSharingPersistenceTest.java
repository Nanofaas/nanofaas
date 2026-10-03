package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class P2pSharingPersistenceTest {
    @TempDir Path dir;
    @Test void fileAndPersistedOverridesTakePrecedenceAndResetRestoresFileBases() throws Exception {
        Path file = dir.resolve("p2p.yaml");
        Files.writeString(file, """
                config:
                  shareFunctions: false
                  shareImages: true
                state:
                  overrides:
                    shareImages: false
                """);
        var props = new P2pProperties(true, "local", 0, "127.0.0.1", List.of(), null, null,
                Duration.ofSeconds(1), Duration.ofSeconds(2), file.toString(), null, true, true, true);
        var settings = new P2pSettings(null, null);
        var service = new P2pService(props, new PeerTable(settings::effective), settings, new SimpleMeterRegistry());
        try {
            service.start();
            assertThat(settings.sharing()).isEqualTo(new P2pSettings.Sharing(false, false, true));
            settings.patch(Map.of("shareFunctions", true));
            service.informationSettingsChanged();
            service.persistNow();
            var saved = new P2pStateFile(file).load();
            assertThat(saved.config().shareFunctions()).isFalse();
            assertThat(saved.state().overrides()).containsEntry("shareFunctions", true).containsEntry("shareImages", false);
            var restored = new P2pSettings(null, null, new P2pSettings.Sharing(false, true, true));
            restored.loadOverrides(saved.state().overrides());
            assertThat(restored.sharing()).isEqualTo(new P2pSettings.Sharing(true, false, true));
            restored.clearOverrides();
            assertThat(restored.sharing()).isEqualTo(new P2pSettings.Sharing(false, true, true));
        } finally { service.stop(); }
    }
}
