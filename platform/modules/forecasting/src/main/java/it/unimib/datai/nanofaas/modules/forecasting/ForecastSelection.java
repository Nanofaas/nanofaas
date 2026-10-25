package it.unimib.datai.nanofaas.modules.forecasting;

import java.util.Objects;

/** The provider answering forecast queries and the trend models' parameters, replaced whole at runtime. */
public final class ForecastSelection {
    public record Settings(ForecastingProperties.Provider provider, TrendForecastSource.Holt holt, TrendForecastSource.Ari ari) {
        public Settings { Objects.requireNonNull(provider); Objects.requireNonNull(holt); Objects.requireNonNull(ari); }
        /** The model the trend source uses: ARI when selected, Holt otherwise. */
        TrendForecastSource.Model trendModel() { return provider == ForecastingProperties.Provider.ARI ? ari : holt; }
    }
    private volatile Settings settings;
    public ForecastSelection(Settings initial) { settings = Objects.requireNonNull(initial); }
    static ForecastSelection from(ForecastingProperties props) {
        var holt = props.holt();
        if (2 * props.ari().order() + 2 > props.history())
            throw new IllegalArgumentException("forecasting history must hold 2 x ARI order + 2 windows");
        return new ForecastSelection(new Settings(props.provider(),
                new TrendForecastSource.Holt(holt.level(), holt.trend(), holt.damping()),
                new TrendForecastSource.Ari(props.ari().order())));
    }
    public Settings get() { return settings; }
    public void set(Settings next) { settings = Objects.requireNonNull(next); }
}
