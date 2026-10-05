package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic immutable future external-arrival trace; revisions never merge partially. */
public final class OracleForecastStore implements ForecastSource {
    public static final int MAX_ENTRIES = 100_000;
    public record Entry(String function, long generation, Instant start, Instant end, double rate, String unit) {}
    public record Trace(int schemaVersion, String nodeId, long revision, String provider, Instant producedAt, List<Entry> entries) {
        public Trace { if (entries != null) entries = List.copyOf(entries); }
    }
    public record Summary(long revision, int entryCount, String nodeId, Instant producedAt) {}
    private record Key(String function, long generation) {}
    private record Stored(Trace trace, Map<Key, List<Entry>> rows) {}
    public static final class RevisionConflict extends RuntimeException {
        public RevisionConflict() { super("forecast revision changed"); }
    }
    private final AtomicReference<Stored> current = new AtomicReference<>();
    private final Clock clock;
    private final Duration maxAge;
    public OracleForecastStore(Clock clock, Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("positive max age required");
        this.clock = Objects.requireNonNull(clock); this.maxAge = maxAge;
    }
    public long revision() { Stored value = current.get(); return value == null ? 0 : value.trace().revision(); }
    public Summary summary() {
        Stored value = current.get();
        return value == null ? new Summary(0, 0, null, null)
                : new Summary(value.trace().revision(), value.trace().entries().size(), value.trace().nodeId(), value.trace().producedAt());
    }
    public void replace(long expectedRevision, Trace trace) {
        Stored before = current.get();
        long revision = before == null ? 0 : before.trace().revision();
        if (expectedRevision != revision) throw new RevisionConflict();
        if (trace == null || trace.schemaVersion() != 1 || !text(trace.nodeId()) || !"oracle".equals(trace.provider())
                || trace.revision() <= expectedRevision || trace.producedAt() == null || trace.entries() == null
                || trace.entries().size() > MAX_ENTRIES) throw new IllegalArgumentException("invalid trace metadata or size");
        Map<Key, List<Entry>> grouped = new HashMap<>();
        for (Entry row : trace.entries()) {
            if (row == null || !text(row.function()) || row.generation() < 1 || row.start() == null || row.end() == null
                    || !row.start().isBefore(row.end()) || !Double.isFinite(row.rate()) || row.rate() < 0 || !"requests/s".equals(row.unit()))
                throw new IllegalArgumentException("invalid forecast row");
            grouped.computeIfAbsent(new Key(row.function(), row.generation()), ignored -> new ArrayList<>()).add(row);
        }
        grouped.replaceAll((key, rows) -> {
            rows.sort(Comparator.comparing(Entry::start));
            for (int i = 1; i < rows.size(); i++)
                if (rows.get(i).start().isBefore(rows.get(i - 1).end())) throw new IllegalArgumentException("overlapping forecast rows");
            return List.copyOf(rows);
        });
        Stored replacement = new Stored(trace, Map.copyOf(grouped));
        if (!current.compareAndSet(before, replacement)) throw new RevisionConflict();
    }
    private static boolean text(String s) { return s != null && !s.isBlank() && s.length() <= 256; }
    @Override public ForecastSnapshot forecast(ForecastQuery query) {
        Stored value = current.get();
        if (value == null || !value.trace().nodeId().equals(query.nodeId())) return missing(query, value);
        Trace trace = value.trace();
        Instant now = clock.instant();
        if (trace.producedAt().isAfter(now) || Duration.between(trace.producedAt(), now).compareTo(maxAge) > 0)
            return new ForecastSnapshot(query, ForecastSnapshot.Status.STALE, null, trace.revision(), "oracle", trace.producedAt());
        List<Entry> rows = value.rows().getOrDefault(new Key(query.function(), query.generation()), List.of());
        Instant cursor = query.start();
        double totalSeconds = seconds(Duration.between(query.start(), query.end()));
        double average = 0;
        for (Entry row : rows) {
            if (!row.end().isAfter(cursor)) continue;
            if (row.start().isAfter(cursor)) break;
            Instant until = row.end().isBefore(query.end()) ? row.end() : query.end();
            average += row.rate() * (seconds(Duration.between(cursor, until)) / totalSeconds);
            cursor = until;
            if (cursor.equals(query.end())) {
                if (!Double.isFinite(average)) return missing(query, value);
                return new ForecastSnapshot(query, ForecastSnapshot.Status.AVAILABLE, average, trace.revision(), "oracle", trace.producedAt());
            }
        }
        return missing(query, value);
    }
    private static double seconds(Duration d) { return d.getSeconds() + d.getNano() / 1_000_000_000.0; }
    private static ForecastSnapshot missing(ForecastQuery query, Stored value) {
        return new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, null, value == null ? 0 : value.trace().revision(),
                "oracle", value == null ? null : value.trace().producedAt());
    }
}
