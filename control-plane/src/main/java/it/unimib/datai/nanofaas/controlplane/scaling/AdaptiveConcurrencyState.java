package it.unimib.datai.nanofaas.controlplane.scaling;

public class AdaptiveConcurrencyState {
    private int targetInFlightPerPod;
    private long lastIncreaseEpochMs;
    private long lastDecreaseEpochMs;
    private long lastReplicaDownEpochMs;

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

    public long lastReplicaDownEpochMs() {
        return lastReplicaDownEpochMs;
    }

    public void lastReplicaDownEpochMs(long lastReplicaDownEpochMs) {
        this.lastReplicaDownEpochMs = lastReplicaDownEpochMs;
    }
}
