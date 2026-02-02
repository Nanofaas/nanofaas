package it.unimib.datai.mcfaas.controlplane.sync;

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

    public void recordDispatch(String functionName, Instant now) {
        globalEvents.addLast(now);
        perFunctionEvents
                .computeIfAbsent(functionName, ignored -> new ConcurrentLinkedDeque<>())
                .addLast(now);
        prune(globalEvents, now);
        prune(perFunctionEvents.get(functionName), now);
    }

    public double estimateWaitSeconds(String functionName, int queueDepth, Instant now) {
        double perFunctionThroughput = throughput(perFunctionEvents.get(functionName), now);
        if (perFunctionSamples(functionName, now) >= perFunctionMinSamples && perFunctionThroughput > 0) {
            return queueDepth / perFunctionThroughput;
        }
        double globalThroughput = throughput(globalEvents, now);
        if (globalThroughput <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return queueDepth / globalThroughput;
    }

    private int perFunctionSamples(String functionName, Instant now) {
        Deque<Instant> events = perFunctionEvents.get(functionName);
        if (events == null) {
            return 0;
        }
        prune(events, now);
        return events.size();
    }

    private double throughput(Deque<Instant> events, Instant now) {
        if (events == null) {
            return 0.0;
        }
        prune(events, now);
        double seconds = Math.max(1.0, window.toSeconds());
        return events.size() / seconds;
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
}
