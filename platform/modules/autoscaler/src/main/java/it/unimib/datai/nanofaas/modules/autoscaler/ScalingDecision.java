package it.unimib.datai.nanofaas.modules.autoscaler;

/**
 * One autoscaling decision.
 *
 * <p>{@code recommendedReplicas} is what the ratio asked for; {@code desiredReplicas} is
 * what survived {@code minReplicas}/{@code maxReplicas}. Keeping both is the difference
 * between "the metric stopped rising" and "the answer was capped" — a distinction the
 * Kubernetes HPA never exposes, because it publishes only the clamped value.
 */
public record ScalingDecision(int currentReplicas,
                              int recommendedReplicas,
                              int desiredReplicas,
                              int effectiveReplicas,
                              double maxRatio,
                              boolean downscaleSignal) {

    /** Whether minReplicas/maxReplicas changed the answer the ratio produced. */
    public boolean limited() {
        return recommendedReplicas != desiredReplicas;
    }
}
