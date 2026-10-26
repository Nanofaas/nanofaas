package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Bounded per-generation complete UTC-aligned windows. Only complete windows known to have been
 * observed reach the model; a subclass turns them into a rate for the queried interval.
 */
public abstract class WindowedForecastSource<S> implements ForecastSource, ExternalArrivalObserver {
    private record Key(String function, long generation) {}
    private final class Series {
        long next;
        final Map<Long, Long> counts = new HashMap<>();
        final S state = newState();
        long revision;
        Instant producedAt;
        Series(long next) { this.next = next; }
    }
    /** Settings every windowed source shares, checked before a (final) subclass reaches this constructor. */
    record Windows(String nodeId, Duration window, Duration maxAge, int maxFunctions, Clock clock) {
        Windows {
            if (nodeId == null || nodeId.isBlank() || window == null || window.getNano() != 0 || window.getSeconds() <= 0
                    || maxAge == null || maxAge.isNegative() || maxAge.isZero() || maxFunctions < 1)
                throw new IllegalArgumentException("valid node, whole-second window, max age and bound required");
            Objects.requireNonNull(clock);
        }
    }
    private final String nodeId;
    private final long windowSeconds;
    private final Duration maxAge;
    private final int maxFunctions;
    private final Clock clock;
    private final Map<Key, Series> series = new HashMap<>();
    WindowedForecastSource(Windows windows) {
        this.nodeId = windows.nodeId(); this.windowSeconds = windows.window().toSeconds();
        this.maxAge = windows.maxAge(); this.maxFunctions = windows.maxFunctions(); this.clock = windows.clock();
    }
    /** Fresh model state of a newly observed generation. */
    protected abstract S newState();
    /** One complete window's rate in requests/s. */
    protected abstract void add(S state, double rate);
    /** A run of complete windows without arrivals; must not cost more than the state it keeps. */
    protected abstract void addZeros(S state, long count);
    protected final long windowSeconds() { return windowSeconds; }
    public synchronized void observe(String function, long generation, Instant at) {
        if (function == null || function.isBlank() || generation < 1) throw new IllegalArgumentException("invalid generation");
        Key key = new Key(function, generation);
        if (series.containsKey(key)) return;
        series.keySet().removeIf(k -> k.function().equals(function) && k.generation() < generation);
        // Unobserved overflow generations remain explicitly MISSING; preserve existing series.
        if (series.size() >= maxFunctions) return;
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
        // Counts are bounded to the current and next window. Zero-only gaps reach the model
        // as one run, while every previously recorded sample is retained.
        for (var entry : new TreeMap<>(value.counts).entrySet()) {
            if (entry.getKey() >= completed) break;
            if (entry.getKey() < value.next) { value.counts.remove(entry.getKey()); continue; }
            zeroWindows(value, entry.getKey() - value.next);
            add(value.state, entry.getValue() / (double) windowSeconds);
            value.next++;
            value.revision++;
            value.producedAt = Instant.ofEpochSecond(Math.multiplyExact(value.next, windowSeconds));
            value.counts.remove(entry.getKey());
        }
        zeroWindows(value, Math.max(0, completed - value.next));
    }
    private void zeroWindows(Series value, long count) {
        if (count == 0) return;
        addZeros(value.state, count);
        value.next += count;
        value.revision += count;
        value.producedAt = Instant.ofEpochSecond(Math.multiplyExact(value.next, windowSeconds));
    }
    /**
     * Capture detached model state and its identity while holding the observation monitor, then
     * estimate without blocking arrivals. The snapshot function must not return mutable series state.
     * The estimator receives the index of the first incomplete window (one step ahead).
     */
    protected final <T> ForecastSnapshot forecast(ForecastQuery query, String provider,
            Function<S, T> snapshotState, BiFunction<T, Long, Double> estimate) {
        T state;
        long next;
        long revision;
        Instant producedAt;
        synchronized (this) {
            Series value = nodeId.equals(query.nodeId()) ? series.get(new Key(query.function(), query.generation())) : null;
            if (value == null) return new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, null, 0, provider, null);
            finalizeWindows(value);
            state = snapshotState.apply(value.state);
            next = value.next;
            revision = value.revision;
            producedAt = value.producedAt;
        }
        Double rate = estimate.apply(state, next);
        if (rate == null) return new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, null, revision, provider, null);
        ForecastSnapshot.Status status = Duration.between(producedAt, clock.instant()).compareTo(maxAge) > 0
                ? ForecastSnapshot.Status.STALE : ForecastSnapshot.Status.AVAILABLE;
        return new ForecastSnapshot(query, status, rate, revision, provider, producedAt);
    }
}
