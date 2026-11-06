package it.unimib.datai.nanofaas.forecastingapi;

/** Numeric Holt path; the caller owns observation windows, generations and forecast validity. */
@FunctionalInterface
public interface HoltForecastAlgorithm {
    /** Returns steps finite nonnegative values without mutating rates; throws on failure. */
    double[] forecast(double[] rates, int steps, double level, double trend, double damping);
}
