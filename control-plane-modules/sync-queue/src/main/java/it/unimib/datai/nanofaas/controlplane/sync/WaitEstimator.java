package it.unimib.datai.nanofaas.controlplane.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class WaitEstimator {
    private final Duration window;
    private final int perFunctionMinSamples;
    private final Deque<Instant> globalEvents = new ConcurrentLinkedDeque<>();
    private final Map<String, Deque<Instant>> perFunctionEvents = new ConcurrentHashMap<>();

    public WaitEstimator(Duration window, int perFunctionMinSamples) {
        this.window = window;
        this.perFunctionMinSamples = perFunctionMinSamples;
    }

    WaitEstimator(Duration window,
                  int perFunctionMinSamples,
                  Deque<Instant> globalEvents,
                  Map<String, ? extends Deque<Instant>> perFunctionEvents) {
        this.window = window;
        this.perFunctionMinSamples = perFunctionMinSamples;
        this.globalEvents.clear();
        this.globalEvents.addAll(globalEvents);
        perFunctionEvents.forEach((functionName, events) -> this.perFunctionEvents.put(functionName, events));
    }

    public void recordDispatch(String functionName, Instant now) {
        globalEvents.addLast(now);
        perFunctionEvents
                .computeIfAbsent(functionName, ignored -> new ConcurrentLinkedDeque<>())
                .addLast(now);
        prune(globalEvents, now);
        prune(perFunctionEvents.get(functionName), now);
    }

    public double estimateWaitSeconds(String functionName, int queueDepth, Instant now) {
        if (queueDepth <= 0) {
            return 0.0;
        }
        ThroughputSnapshot perFunction = snapshot(perFunctionEvents.get(functionName), now);
        if (perFunction.samples() >= perFunctionMinSamples && perFunction.throughput() > 0) {
            return queueDepth / perFunction.throughput();
        }
        ThroughputSnapshot global = snapshot(globalEvents, now);
        if (global.throughput() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return queueDepth / global.throughput();
    }

    private ThroughputSnapshot snapshot(Deque<Instant> events, Instant now) {
        if (events == null) {
            return new ThroughputSnapshot(0, 0.0);
        }
        prune(events, now);
        int samples = events.size();
        double seconds = Math.max(1.0, window.toSeconds());
        return new ThroughputSnapshot(samples, samples / seconds);
    }

    private void prune(Deque<Instant> events, Instant now) {
        if (events == null) {
            return;
        }
        Instant cutoff = now.minus(window);
        while (true) {
            Instant first = events.peekFirst();
            if (first == null || !first.isBefore(cutoff)) {
                return;
            }
            events.pollFirst();
        }
    }

    private record ThroughputSnapshot(int samples, double throughput) {
    }
}
