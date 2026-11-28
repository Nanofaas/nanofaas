package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;

/** Reads the wake-up path's state through the meters production exposes, as an operator would. */
public final class WakeUpMeters {
    private WakeUpMeters() {
    }

    /** {@code deployment_wakeups_in_flight}: the gate's wake-ups under way. */
    public static long inFlight(DeploymentWakeUpGate gate) {
        return gauge(gate, "deployment_wakeups_in_flight");
    }

    /** {@code deployment_wakeup_protected_functions}: generations protected from scale-down. */
    public static long protectedFunctions(DeploymentWakeUpCoordinator coordinator) {
        return gauge(coordinator, "deployment_wakeup_protected_functions");
    }

    /** {@code deployment_wakeup_tracked_generations}: generations the coordinator holds state for. */
    public static long trackedGenerations(DeploymentWakeUpCoordinator coordinator) {
        return gauge(coordinator, "deployment_wakeup_tracked_generations");
    }

    private static long gauge(MeterBinder binder, String name) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);
        return (long) registry.get(name).gauge().value();
    }
}
