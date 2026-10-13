package it.unimib.datai.nanofaas.modules.offload.oneshot.api;

import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ObservationSettingsTest {
    @Test void observationBudgetsPreserveResourcesAndRoundCount() {
        var base = OneShotConfigurationTest.config();
        var negotiation = new EpochSettings(Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofSeconds(5), 8, 4, 64, 16, 1);
        var settings = new OneShotSettings(base.schemaVersion(), base.profileId(), base.environmentFingerprint(),
                base.purpose(), base.allowSynthetic(), base.cloudUri(), base.memoryCapacityMiB(), base.flowQuantum(),
                Duration.ofSeconds(360), Duration.ofSeconds(90), false, base.anchor(), Duration.ofSeconds(10),
                1, base.burst(), base.functions(), negotiation, 10000000, 64L*1024*1024);
        assertThat(settings.memoryCapacityMiB()).isEqualTo(base.memoryCapacityMiB());
        assertThat(settings.negotiation().maxRounds()).isEqualTo(8);
        for (double invalid : new double[]{0, -1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new EpochSettings(Duration.ofSeconds(60), Duration.ofSeconds(5),
                    Duration.ofSeconds(5), 8, 4, 64, 16, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
