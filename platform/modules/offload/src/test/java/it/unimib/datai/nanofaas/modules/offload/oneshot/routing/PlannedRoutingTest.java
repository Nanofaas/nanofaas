package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PlannedRoutingTest {
    @Test
    void rouletteUsesHalfOpenIntervalsAndNeverSelectsZeroWeight() {
        var weights = new LinkedHashMap<String, Double>();
        weights.put("local", 6.0);
        weights.put("unused", 0.0);
        weights.put("neighbor", 3.0);
        weights.put("cloud", 1.0);
        var picker = new PlanRouter.WeightedPicker(weights);
        assertThat(picker.next(0)).isEqualTo("local");
        assertThat(picker.next(.59)).isEqualTo("local");
        assertThat(picker.next(.60)).isEqualTo("neighbor");
        assertThat(picker.next(.89)).isEqualTo("neighbor");
        assertThat(picker.next(.90)).isEqualTo("cloud");
        assertThat(picker.next(.999)).isEqualTo("cloud");
        assertThat(picker.next(Math.nextDown(1.0))).isEqualTo("cloud");
    }

    @Test
    void invalidWeightsAndDrawsAreRejectedInsteadOfSilentlyRouting() {
        for (double weight : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new PlanRouter.WeightedPicker(Map.of("local", weight)));
        }
        var picker = new PlanRouter.WeightedPicker(Map.of("local", 1.0));
        for (double draw : new double[]{-1, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> picker.next(draw));
        }
    }
}
