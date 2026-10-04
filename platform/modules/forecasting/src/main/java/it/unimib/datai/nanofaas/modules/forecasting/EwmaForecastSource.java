package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import java.time.*;
import java.util.*;

/** Bounded per-generation windows. Only complete windows known to have been observed enter the mean. */
public final class EwmaForecastSource implements ForecastSource, ExternalArrivalObserver {
    private record Key(String function, long generation) {}
    private static final class Series {
        long next;
        final Map<Long, Long> counts = new HashMap<>();
        Double mean;
        long revision;
        Instant producedAt;
        Series(long next) { this.next = next; }
    }
    private final String nodeId;
    private final double alpha;
    private final long windowSeconds;
    private final Duration maxAge;
    private final int maxFunctions;
    private final Clock clock;
    private final Map<Key, Series> series = new HashMap<>();
    public EwmaForecastSource(String nodeId, double alpha, Duration window, Duration maxAge, int maxFunctions, Clock clock) {
        if (nodeId == null || nodeId.isBlank() || !Double.isFinite(alpha) || alpha <= 0 || alpha > 1
                || window == null || window.getNano() != 0 || window.getSeconds() <= 0
                || maxAge == null || maxAge.isNegative() || maxAge.isZero() || maxFunctions < 1)
            throw new IllegalArgumentException("valid EWMA node, alpha, whole-second window, max age and bound required");
        this.nodeId = nodeId; this.alpha = alpha; this.windowSeconds = window.getSeconds();
        this.maxAge = maxAge; this.maxFunctions = maxFunctions; this.clock = Objects.requireNonNull(clock);
    }
    public synchronized void observe(String function, long generation, Instant at) {
        if (function == null || function.isBlank() || generation < 1) throw new IllegalArgumentException("invalid generation");
        Key key = new Key(function, generation);
        if (series.containsKey(key)) return;
        series.keySet().removeIf(k -> k.function().equals(function) && k.generation() < generation);
        if (series.size() >= maxFunctions) throw new IllegalStateException("forecast function bound reached");
        long first = Math.floorDiv(at.getEpochSecond(), windowSeconds);
        if (at.getEpochSecond() % windowSeconds != 0 || at.getNano() != 0) first++;
        series.put(key, new Series(first));
    }
    public synchronized void retain(Set<String> activeIdentities) {
        series.keySet().removeIf(k -> !activeIdentities.contains(k.function() + "#" + k.generation()));
    }
    @Override public synchronized void record(ExternalArrival arrival) {
        Series value = series.get(new Key(arrival.function(), arrival.generation()));
        if (value == null) return;
        finalizeWindows(value);
        long index = Math.floorDiv(arrival.at().getEpochSecond(), windowSeconds);
        long now = Math.floorDiv(clock.instant().getEpochSecond(), windowSeconds);
        if (index < value.next || index > now + 1) return;
        value.counts.merge(index, 1L, Math::addExact);
    }
    private void finalizeWindows(Series value) {
        long completed = Math.floorDiv(clock.instant().getEpochSecond(), windowSeconds);
        // Counts are bounded to the current and next window. Decay zero-only
        // gaps analytically while retaining every previously recorded sample.
        for (var entry : new TreeMap<>(value.counts).entrySet()) {
            if (entry.getKey() >= completed) break;
            if (entry.getKey() < value.next) { value.counts.remove(entry.getKey()); continue; }
            zeroWindows(value, entry.getKey() - value.next);
            double rate = entry.getValue() / (double) windowSeconds;
            value.mean = value.mean == null ? rate : alpha * rate + (1 - alpha) * value.mean;
            value.next++;
            value.revision++;
            value.producedAt = Instant.ofEpochSecond(Math.multiplyExact(value.next, windowSeconds));
            value.counts.remove(entry.getKey());
        }
        zeroWindows(value, Math.max(0, completed - value.next));
    }
    private void zeroWindows(Series value, long count) {
        if (count == 0) return;
        value.mean = value.mean == null ? 0 : value.mean * Math.pow(1 - alpha, count);
        value.next += count;
        value.revision += count;
        value.producedAt = Instant.ofEpochSecond(Math.multiplyExact(value.next, windowSeconds));
    }
    @Override public synchronized ForecastSnapshot forecast(ForecastQuery query) {
        Series value = nodeId.equals(query.nodeId()) ? series.get(new Key(query.function(), query.generation())) : null;
        if (value == null) return new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, null, 0, "ewma", null);
        finalizeWindows(value);
        if (value.mean == null) return new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, null, value.revision, "ewma", null);
        ForecastSnapshot.Status status = Duration.between(value.producedAt, clock.instant()).compareTo(maxAge) > 0
                ? ForecastSnapshot.Status.STALE : ForecastSnapshot.Status.AVAILABLE;
        return new ForecastSnapshot(query, status, value.mean, value.revision, "ewma", value.producedAt);
    }
}
