package it.unimib.datai.nanofaas.modules.autoscaler;

public record ScalingDecision(int currentReplicas,
                              int desiredReplicas,
                              int effectiveReplicas,
                              double maxRatio,
                              boolean downscaleSignal) {
}
