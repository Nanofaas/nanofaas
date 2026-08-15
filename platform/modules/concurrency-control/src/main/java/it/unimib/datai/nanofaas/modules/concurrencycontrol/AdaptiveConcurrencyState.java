package it.unimib.datai.nanofaas.modules.concurrencycontrol;

/**
 * Per-function state of the adaptive controller: the current per-replica target, the cooldown
 * timestamps, and the latency baseline plus the timer readings the baseline is derived from.
 * Mutated only from the governor's single-threaded tick loop.
 */
public class AdaptiveConcurrencyState {
    private int targetInFlightPerPod;
    private long lastIncreaseEpochMs;
    private long lastDecreaseEpochMs;
    private long lastLatencyCount;
    private double lastLatencyTotalMs;
    private double baselineLatencyMs;

    public AdaptiveConcurrencyState(int initialTargetInFlightPerPod) {
        this.targetInFlightPerPod = initialTargetInFlightPerPod;
    }

    public int targetInFlightPerPod() {
        return targetInFlightPerPod;
    }

    public void targetInFlightPerPod(int targetInFlightPerPod) {
        this.targetInFlightPerPod = targetInFlightPerPod;
    }

    public long lastIncreaseEpochMs() {
        return lastIncreaseEpochMs;
    }

    public void lastIncreaseEpochMs(long lastIncreaseEpochMs) {
        this.lastIncreaseEpochMs = lastIncreaseEpochMs;
    }

    public long lastDecreaseEpochMs() {
        return lastDecreaseEpochMs;
    }

    public void lastDecreaseEpochMs(long lastDecreaseEpochMs) {
        this.lastDecreaseEpochMs = lastDecreaseEpochMs;
    }

    public long lastLatencyCount() {
        return lastLatencyCount;
    }

    public void lastLatencyCount(long lastLatencyCount) {
        this.lastLatencyCount = lastLatencyCount;
    }

    public double lastLatencyTotalMs() {
        return lastLatencyTotalMs;
    }

    public void lastLatencyTotalMs(double lastLatencyTotalMs) {
        this.lastLatencyTotalMs = lastLatencyTotalMs;
    }

    public double baselineLatencyMs() {
        return baselineLatencyMs;
    }

    public void baselineLatencyMs(double baselineLatencyMs) {
        this.baselineLatencyMs = baselineLatencyMs;
    }
}
