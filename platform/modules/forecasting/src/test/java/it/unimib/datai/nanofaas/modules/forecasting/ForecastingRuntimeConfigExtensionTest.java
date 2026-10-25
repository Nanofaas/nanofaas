package it.unimib.datai.nanofaas.modules.forecasting;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class ForecastingRuntimeConfigExtensionTest {
    final ForecastSelection selection = new ForecastSelection(new ForecastSelection.Settings(
            ForecastingProperties.Provider.EWMA, new TrendForecastSource.Holt(0.4, 0.4, 0.95), new TrendForecastSource.Ari(6)));
    final ForecastingRuntimeConfigExtension extension = new ForecastingRuntimeConfigExtension(selection, 20);
    @Test void snapshotNamesTheProviderItsAlternativesAndTheModelParameters() {
        assertThat(extension.namespace()).isEqualTo("forecasting");
        assertThat(extension.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "provider", "ewma", "available", List.of("ewma", "oracle", "holt", "ari"),
                "holtLevel", 0.4, "holtTrend", 0.4, "holtDamping", 0.95, "ariOrder", 6, "persistence", "restart"));
    }
    @Test void invalidPatchesAreRefusedWithReasons() {
        assertThat(extension.validate(Map.of())).containsExactly("forecasting patch must not be empty");
        assertThat(extension.validate(Map.of("alpha", 0.3))).containsExactly("forecasting patch contains unsupported keys");
        assertThat(extension.validate(Map.of("provider", "arima"))).containsExactly("provider must be one of the available providers");
        assertThat(extension.validate(Map.of("provider", "HOLT"))).hasSize(1);
        assertThat(extension.validate(Map.of("holtLevel", 0, "holtTrend", 1.5, "holtDamping", "high"))).hasSize(3);
        // A history of 20 windows fits at most order 9.
        assertThat(extension.validate(Map.of("ariOrder", 10))).containsExactly("ariOrder must be an integer in [1, 9]");
        assertThat(extension.validate(Map.of("ariOrder", 2.5))).hasSize(1);
        assertThat(extension.validate(Map.of("provider", "ari", "ariOrder", 9, "holtDamping", 1))).isEmpty();
    }
    @Test void applySwitchesTheSelectionAndRestoreReturnsToTheSnapshot() {
        var before = extension.snapshot();
        extension.apply(Map.of("provider", "ari", "ariOrder", 3));
        assertThat(selection.get().provider()).isEqualTo(ForecastingProperties.Provider.ARI);
        assertThat(selection.get().trendModel()).isEqualTo(new TrendForecastSource.Ari(3));
        extension.apply(Map.of("provider", "holt", "holtTrend", 0.1));
        assertThat(selection.get().trendModel()).isEqualTo(new TrendForecastSource.Holt(0.4, 0.1, 0.95));
        extension.restore(before);
        assertThat(extension.snapshot()).isEqualTo(before);
    }
}
