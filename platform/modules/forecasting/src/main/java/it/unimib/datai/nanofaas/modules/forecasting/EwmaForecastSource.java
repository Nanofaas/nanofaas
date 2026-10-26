package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import java.time.*;

/** Exponentially weighted mean of the complete windows; the same rate for any queried interval. */
public final class EwmaForecastSource extends WindowedForecastSource<EwmaForecastSource.Mean> {
    static final class Mean { Double value; }
    private final double alpha;
    public EwmaForecastSource(String nodeId, double alpha, Duration window, Duration maxAge, int maxFunctions, Clock clock) {
        super(new Windows(nodeId, window, maxAge, maxFunctions, clock));
        if (!Double.isFinite(alpha) || alpha <= 0 || alpha > 1) throw new IllegalArgumentException("EWMA alpha must be in (0, 1]");
        this.alpha = alpha;
    }
    @Override protected Mean newState() { return new Mean(); }
    @Override protected void add(Mean mean, double rate) {
        mean.value = mean.value == null ? rate : alpha * rate + (1 - alpha) * mean.value;
    }
    // Decay zero-only gaps analytically instead of iterating them.
    @Override protected void addZeros(Mean mean, long count) {
        mean.value = mean.value == null ? 0 : mean.value * Math.pow(1 - alpha, (double) count);
    }
    @Override public ForecastSnapshot forecast(ForecastQuery query) {
        return forecast(query, "ewma", mean -> mean.value, (rate, next) -> rate);
    }
}
