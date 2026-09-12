package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.util.function.BooleanSupplier;

/**
 * The wake-up state a scaler has to respect before scaling a managed deployment down.
 *
 * <p>A cold-start wake-up holds a lease on the generation it woke. A scaler that ignored it
 * would undo an in-flight invocation's own scale-up, so the downscale is offered as a guarded
 * operation rather than as a question the caller could ask and then act on stale.</p>
 */
public interface DeploymentWakeUpControl {

    /**
     * Runs {@code scaleDown} only while the active generation holds no wake-up lease.
     *
     * @return what {@code scaleDown} returned, or {@code false} when it was not run at all
     */
    boolean scaleDownIfUnprotected(FunctionGeneration generation,
                                   ManagedDeploymentTarget target,
                                   BooleanSupplier scaleDown);

    /** Drops the wake-up state a removed function owned. A late wake-up must not recreate it. */
    void removeFunctionState(String functionName);
}
