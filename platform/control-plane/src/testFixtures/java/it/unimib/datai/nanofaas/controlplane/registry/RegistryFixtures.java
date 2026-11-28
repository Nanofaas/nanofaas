package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionFixtures;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;

/**
 * Builds the function service and its coordinator through their production constructors, filling
 * in for tests what the application context always provides: an open restore gate, the default
 * execution lifetime and a coordinator.
 */
public final class RegistryFixtures {
    /** The execution store's default max-lifetime, which registration checks attempt budgets against. */
    public static final Duration MAX_LIFETIME = ExecutionFixtures.defaults().maxLifetime();

    private RegistryFixtures() {
    }

    /** A restore gate already open, as it is once the catalog restorer has run. */
    public static FunctionRestoreGate openGate() {
        FunctionRestoreGate gate = new FunctionRestoreGate();
        gate.markReady();
        return gate;
    }

    /**
     * A coordinator on a capacity registry of its own, for a test that does not share one with the
     * code under test. Its replica readings come from a snapshot owning no refresh pool, so there is
     * nothing to close.
     */
    public static ManagedDeploymentCoordinator coordinator(DeploymentProviderResolver resolver,
                                                           FunctionRegistry registry,
                                                           FunctionOperationLocks locks) {
        return new ManagedDeploymentCoordinator(resolver, registry, locks, new FunctionCapacityRegistry(),
                ReplicaStatusSnapshot.withoutRefreshCapacity(InstantSource.system()));
    }

    public static FunctionService functionService(FunctionRegistry registry, FunctionDefaults defaults,
                                                  ImageValidator imageValidator,
                                                  List<FunctionRegistrationListener> listeners,
                                                  DeploymentProviderResolver resolver) {
        FunctionOperationLocks locks = new FunctionOperationLocks();
        return functionService(registry, defaults, imageValidator, listeners, resolver, locks,
                coordinator(resolver, registry, locks));
    }

    public static FunctionService functionService(FunctionRegistry registry, FunctionDefaults defaults,
                                                  ImageValidator imageValidator,
                                                  List<FunctionRegistrationListener> listeners,
                                                  DeploymentProviderResolver resolver,
                                                  FunctionOperationLocks locks,
                                                  ManagedDeploymentCoordinator coordinator) {
        return new FunctionService(registry, defaults, imageValidator, listeners, resolver, locks,
                coordinator, openGate(), MAX_LIFETIME);
    }
}
