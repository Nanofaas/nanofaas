package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Forecasts that depend on how far ahead the queried interval lies, computed from the latest
 * retained complete windows: Holt's damped trend, or an autoregression of the changes between
 * consecutive windows (ARI). The answer is the duration-weighted mean, over the queried interval,
 * of the forecasts for the windows it covers.
 */
public final class TrendForecastSource extends WindowedForecastSource<TrendForecastSource.History> {
    /** Windows further ahead than this reuse the last computed forecast. */
    static final int MAX_STEPS = 4096;
    /** ARI keeps each forecast within twice the largest rate of this many recent windows. */
    static final int CAP_WINDOWS = 60;
    public sealed interface Model permits Holt, Ari {}
    /** Level, trend and trend-damping weights, each in (0, 1]; damping 1 extends the trend undamped. */
    public record Holt(double level, double trend, double damping) implements Model {
        public Holt {
            if (!unit(level) || !unit(trend) || !unit(damping)) throw new IllegalArgumentException("Holt weights must be in (0, 1]");
        }
    }
    /** Autoregression of the given order on window-to-window changes, with an intercept. */
    public record Ari(int order) implements Model {
        public Ari { if (order < 1 || order > 64) throw new IllegalArgumentException("ARI order must be in [1, 64]"); }
    }
    static final class History {
        final double[] rates;
        int size, head;
        History(int capacity) { rates = new double[capacity]; }
        void add(double rate) { rates[head] = rate; head = (head + 1) % rates.length; size = Math.min(size + 1, rates.length); }
        double[] chronological() {
            double[] out = new double[size];
            for (int i = 0; i < size; i++) out[i] = rates[Math.floorMod(head - size + i, rates.length)];
            return out;
        }
    }
    private final int history;
    private final Supplier<Model> model;
    /** {@code history} complete windows are retained per generation; ARI of order p needs 2p+2. */
    public TrendForecastSource(String nodeId, Duration window, Duration maxAge, int maxFunctions, int history,
                               Supplier<Model> model, Clock clock) {
        super(new Windows(nodeId, window, maxAge, maxFunctions, clock));
        if (history < 4 || history > 100_000) throw new IllegalArgumentException("history must be in [4, 100000] windows");
        this.history = history; this.model = Objects.requireNonNull(model);
        Objects.requireNonNull(model.get());
    }
    static boolean unit(double value) { return Double.isFinite(value) && value > 0 && value <= 1; }
    // One query reads the model once, so its rate and provider name always agree.
    @Override public ForecastSnapshot forecast(ForecastQuery query) {
        return forecast(query, model.get());
    }
    ForecastSnapshot forecast(ForecastQuery query, Model selected) {
        Objects.requireNonNull(selected);
        return forecast(query, selected instanceof Holt ? "holt" : "ari", History::chronological,
                (rates, next) -> estimate(rates, next, query, selected));
    }
    @Override protected History newState() { return new History(history); }
    @Override protected void add(History state, double rate) { state.add(rate); }
    // Only retained windows matter: a longer gap equals a full history of zeros.
    @Override protected void addZeros(History state, long count) { for (long i = Math.min(count, history); i > 0; i--) state.add(0); }
    private Double estimate(double[] rates, long next, ForecastQuery query, Model selected) {
        if (rates.length == 0) return null;
        long window = Math.multiplyExact(windowSeconds(), 1_000_000_000L);
        long origin = Math.multiplyExact(next, window);
        long from = nanos(query.start()) - origin, until = nanos(query.end()) - origin;
        int steps = (int) Math.min(MAX_STEPS, Math.max(1, Math.ceilDiv(until, window)));
        double[] path = switch (selected) {
            case Holt holt -> holt(holt, rates, steps);
            case Ari ari -> ari(ari, rates, steps);
        };
        return mean(path, window, from, until);
    }
    private static long nanos(Instant at) { return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano()); }
    /** Weights step s by its overlap with [from, until); step 1 also covers anything before it, the last step anything after. */
    static double mean(double[] path, long window, long from, long until) {
        double total = 0;
        for (int s = 0; s < path.length; s++) {
            long lo = s == 0 ? from : Math.max(from, s * window);
            long hi = s == path.length - 1 ? until : Math.min(until, (s + 1) * window);
            if (hi > lo) total += (double) (hi - lo) * path[s];
        }
        return total / (until - from);
    }
    static double[] holt(Holt model, double[] rates, int steps) {
        double level = rates[0], slope = 0;
        for (int i = 1; i < rates.length; i++) {
            double previous = level;
            level = model.level() * rates[i] + (1 - model.level()) * (level + model.damping() * slope);
            slope = model.trend() * (level - previous) + (1 - model.trend()) * model.damping() * slope;
        }
        double[] path = new double[steps];
        double damped = 0, factor = 1;
        for (int s = 0; s < steps; s++) {
            factor *= model.damping(); damped += factor;
            path[s] = Math.max(0, level + damped * slope);
        }
        return path;
    }
    static double[] ari(Ari model, double[] rates, int steps) {
        int p = model.order(), n = rates.length;
        double[] path = new double[steps];
        double level = rates[n - 1];
        double[] coef = n < 2 * p + 2 ? null : fit(rates, p);
        // Too short a history, or a degenerate fit: hold the latest window's rate.
        if (coef == null) { Arrays.fill(path, level); return path; }
        double cap = 0;
        for (int i = Math.max(0, n - CAP_WINDOWS); i < n; i++) cap = Math.max(cap, rates[i]);
        cap *= 2;
        double[] lags = new double[p];
        for (int j = 0; j < p; j++) lags[j] = rates[n - 1 - j] - rates[n - 2 - j];
        for (int s = 0; s < steps; s++) {
            double change = coef[0];
            for (int j = 0; j < p; j++) change += coef[j + 1] * lags[j];
            // A fitted autoregression can be explosive; the cap keeps it within the recent range.
            double nextLevel = Math.min(cap, Math.max(0, level + change));
            System.arraycopy(lags, 0, lags, 1, p - 1);
            lags[0] = nextLevel - level;
            level = nextLevel;
            path[s] = level;
        }
        return path;
    }
    /** Least squares of each change on an intercept and the p previous changes, slightly ridged. */
    static double[] fit(double[] rates, int p) {
        int k = p + 1;
        double[] changes = new double[rates.length - 1];
        for (int i = 1; i < rates.length; i++) changes[i - 1] = rates[i] - rates[i - 1];
        double[][] system = new double[k][k + 1];
        double[] row = new double[k];
        for (int t = p; t < changes.length; t++) {
            row[0] = 1;
            for (int j = 1; j <= p; j++) row[j] = changes[t - j];
            for (int r = 0; r < k; r++) {
                for (int c = 0; c < k; c++) system[r][c] += row[r] * row[c];
                system[r][k] += row[r] * changes[t];
            }
        }
        double largest = 0;
        for (int r = 0; r < k; r++) largest = Math.max(largest, system[r][r]);
        for (int r = 0; r < k; r++) system[r][r] += 1e-9 * (1 + largest);
        double[] coef = solve(system);
        if (coef == null) return null;
        for (double value : coef) if (!Double.isFinite(value)) return null;
        return coef;
    }
    /** Gaussian elimination with partial pivoting on an augmented k x (k+1) system. */
    private static double[] solve(double[][] system) {
        int k = system.length;
        for (int col = 0; col < k; col++) {
            int pivot = col;
            for (int r = col + 1; r < k; r++) if (Math.abs(system[r][col]) > Math.abs(system[pivot][col])) pivot = r;
            double[] swap = system[col]; system[col] = system[pivot]; system[pivot] = swap;
            if (system[col][col] == 0) return null;
            for (int r = col + 1; r < k; r++) {
                double factor = system[r][col] / system[col][col];
                for (int c = col; c <= k; c++) system[r][c] -= factor * system[col][c];
            }
        }
        double[] solution = new double[k];
        for (int r = k - 1; r >= 0; r--) {
            double sum = system[r][k];
            for (int c = r + 1; c < k; c++) sum -= system[r][c] * solution[c];
            solution[r] = sum / system[r][r];
        }
        return solution;
    }
}
