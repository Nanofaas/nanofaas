package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;

public interface ScalingMetricsSource {

    int queueDepth(String functionName);

    int inFlight(String functionName);

    void setEffectiveConcurrency(String functionName, int value);

    void updateConcurrencyController(
            String functionName,
            ConcurrencyControlMode mode,
            int targetInFlightPerPod
    );

    /**
     * Whether this source reports real queue state.
     *
     * <p>The core registers {@link #noOp()} whenever no module supplies one, so a bean of this
     * type is always present and {@code @ConditionalOnBean(ScalingMetricsSource.class)} can never
     * tell the two apart. A consumer that cannot work against zeroes must ask here instead:
     * the no-op answers every reading with {@code 0} and discards every write, which is
     * indistinguishable from an idle system.
     */
    default boolean enabled() {
        return true;
    }

    static ScalingMetricsSource noOp() {
        return NoOpScalingMetricsSource.INSTANCE;
    }
}
