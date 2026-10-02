package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import java.util.ArrayDeque;

/** Sliding window of RTT samples; the median resists single outliers. Not thread-safe. */
final class RttWindow {
    private final int size;
    private final ArrayDeque<Double> samples = new ArrayDeque<>();

    RttWindow(int size) {
        this.size = size;
    }

    void add(double rttMs) {
        if (samples.size() == size) {
            samples.removeFirst();
        }
        samples.addLast(rttMs);
    }

    Double median() {
        if (samples.isEmpty()) return null;
        double[] s = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }
}
