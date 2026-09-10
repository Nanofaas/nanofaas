package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class FunctionService {
    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);

    private final FunctionRegistry registry;
    private final FunctionSpecResolver resolver;
    private final DeploymentProviderResolver deploymentProviderResolver;
    private final ManagedDeploymentCoordinator managedDeploymentCoordinator;
    private final ImageValidator imageValidator;
    private final List<FunctionRegistrationListener> listeners;
    private final FunctionOperationLocks locks;
    private final FunctionRestoreGate restoreGate;
    private final FunctionApplicationState applicationState;

    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver) {
        this(registry, defaults, imageValidator, listeners, deploymentProviderResolver,
                new FunctionOperationLocks(), null, null);
    }

    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver,
                           FunctionOperationLocks locks,
                           ManagedDeploymentCoordinator managedDeploymentCoordinator) {
        this(registry, defaults, imageValidator, listeners, deploymentProviderResolver,
                locks, managedDeploymentCoordinator, null);
    }

    @Autowired
    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver,
                           FunctionOperationLocks locks,
                           @Autowired(required = false) ManagedDeploymentCoordinator managedDeploymentCoordinator,
                           @Autowired(required = false) FunctionRestoreGate restoreGate) {
        this.registry = registry;
        this.applicationState = registry.applicationState();
        this.resolver = new FunctionSpecResolver(defaults);
        this.deploymentProviderResolver = deploymentProviderResolver;
        this.locks = locks;
        this.managedDeploymentCoordinator = managedDeploymentCoordinator == null
                ? new ManagedDeploymentCoordinator(deploymentProviderResolver, registry, locks)
                : managedDeploymentCoordinator;
        this.imageValidator = imageValidator;
        this.listeners = listeners == null ? List.of() : listeners;
        this.restoreGate = restoreGate == null ? FunctionRestoreGate.open() : restoreGate;
    }

    private void ensureReady() {
        if (!restoreGate.isReady()) {
            throw new IllegalStateException("Control plane is still restoring the function catalog");
        }
    }

    /**
     * Fences everything that would lead into a deployment being torn down. The one operation that
     * must still get through is {@link #remove}: it is the retry that ends the pending state.
     */
    private void requireNotPendingRemoval(String name) {
        applicationState.requireAvailable(name);
    }

    public Collection<FunctionSpec> list() {
        return registry.listRegistered().stream()
                .filter(function -> !applicationState.isUnavailable(function.name()))
                .map(RegisteredFunction::spec)
                .toList();
    }

    public Collection<RegisteredFunction> listRegistered() {
        return registry.listRegistered().stream()
                .filter(function -> !applicationState.isUnavailable(function.name()))
                .toList();
    }

    /**
     * The lookup the invocation path uses. A function in pending removal is refused explicitly
     * here: its generation is retired and its endpoint is closed, so dispatching into it would only
     * produce a transport error the caller cannot interpret.
     */
    public Optional<FunctionSpec> get(String name) {
        requireNotPendingRemoval(name);
        return registry.get(name);
    }

    public Optional<RegisteredFunction> getRegistered(String name) {
        if (applicationState.isUnavailable(name)) {
            return Optional.empty();
        }
        return registry.getRegistered(name);
    }

    public Optional<RegisteredFunction> register(FunctionSpec spec) {
        ensureReady();
        FunctionSpec initialResolved = resolver.resolve(spec);

        return locks.withLock(initialResolved.name(), () -> {
            requireNotPendingRemoval(initialResolved.name());
            if (registry.getRegistered(initialResolved.name()).isPresent()) {
                return Optional.empty();
            }

            imageValidator.validate(initialResolved);
            RegisteredFunction registered = resolveRegistration(initialResolved);

            List<FunctionRegistrationListener> notified = new ArrayList<>();
            try {
                for (FunctionRegistrationListener listener : listeners) {
                    listener.onRegister(registered.spec());
                    notified.add(listener);
                }
                registry.put(registered); // durable commit
                // Re-registration (or first registration) replaces any cached replica status from a
                // previous incarnation of the same name, and orphans an in-flight refresh for it.
                registered.managedDeploymentTarget().ifPresent(managedDeploymentCoordinator::invalidate);
                return Optional.of(registered);
            } catch (RuntimeException failure) {
                rollbackRegistrationListeners(registered.name(), notified, failure);
                rollbackProvisionedRegistration(registered, failure);
                throw failure;
            }
        });
    }

    /**
     * Applies a partial update to a registered function. Only control-plane knobs are mutable
     * (see {@link FunctionUpdateRequest}), so the deployment is left untouched and the function
     * keeps serving throughout.
     *
     * @return the updated function, or empty if it is not registered
     */
    public Optional<RegisteredFunction> update(String name, FunctionUpdateRequest request) {
        ensureReady();
        return locks.withLock(name, () -> {
            requireNotPendingRemoval(name);
            RegisteredFunction existing = registry.getRegistered(name).orElse(null);
            if (existing == null) {
                return Optional.empty();
            }

            FunctionSpec updatedSpec = resolver.resolve(request.applyTo(existing.spec()));
            RegisteredFunction updated = new RegisteredFunction(updatedSpec, existing.deploymentMetadata());
            boolean applicationPending = applicationState.isUpdatePending(updated);
            if (updated.equals(existing) && !applicationPending) {
                return Optional.of(existing);
            }
            if (!updated.equals(existing)) {
                registry.put(updated);
            }
            applicationState.markUpdate(updated);
            // A managed backend derived its runtime tuning from the spec it was provisioned with;
            // the container proxy's single-hop timeout and admission bound are exactly that. Without
            // this the deployment keeps enforcing the original values while the caller believes the
            // patched ones — a 30 s hop cut on a function the operator just gave 120 s.
            managedDeploymentTarget(updated).ifPresent(target ->
                    managedDeploymentCoordinator.requireProvider(target).updateSpec(updatedSpec));
            // ponytail: no rollback on listener failure — the registration rollback path deletes the
            // function's queue, which is far worse than a listener missing one update. Listeners are
            // idempotent, so replaying the same PATCH converges.
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRegister(updatedSpec);
            }
            applicationState.completeUpdate(updated);
            log.info("Updated function {} (concurrency={}, timeoutMs={}, maxRetries={})",
                    name, updatedSpec.concurrency(), updatedSpec.timeoutMs(), updatedSpec.maxRetries());
            return Optional.of(updated);
        });
    }

    /**
     * Sets the replica count for a DEPLOYMENT-mode function.
     * Returns the new replica count, or empty if function not found.
     * Throws IllegalArgumentException if function is not in DEPLOYMENT mode.
     * Throws IllegalStateException if the effective deployment provider is not available.
     */
    public Optional<Integer> setReplicas(String name, int replicas) {
        ensureReady();
        return locks.withLock(name, () -> {
            requireNotPendingRemoval(name);
            RegisteredFunction function = registry.getRegistered(name).orElse(null);
            if (function == null) {
                return Optional.empty();
            }
            if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
                throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
            }
            if (!managedDeploymentCoordinator.setReplicas(requireManagedDeploymentTarget(function), replicas)) {
                return Optional.empty();
            }
            log.info("Set replicas for function {} to {}", name, replicas);
            return Optional.of(replicas);
        });
    }

    public Optional<ReplicaStatus> getReplicaStatus(String name) {
        return locks.withLock(name, () -> {
            requireNotPendingRemoval(name);
            RegisteredFunction function = registry.getRegistered(name).orElse(null);
            if (function == null) {
                return Optional.empty();
            }
            if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
                throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
            }
            return Optional.of(managedDeploymentCoordinator.getFreshReplicaStatus(requireManagedDeploymentTarget(function)));
        });
    }

    /**
     * Removes a function and the deployment behind it.
     *
     * <p>Two failure outcomes are distinguished, and they are not interchangeable. If the backend
     * reports a {@link PartialDeprovisionException} — resources it could not delete — the removal
     * enters <em>pending removal</em>: nothing pretends the function came back, and a retry of this
     * same call resumes the cleanup. Other provider failures restore the function; after a completed
     * deprovision, restoration is allowed only when reconcile succeeds. Otherwise the durable record
     * stays as an unavailable recovery handle (see {@link #rollbackRemoval}).
     *
     * @throws FunctionRemovalPendingException when the backend deprovisioned only part of the
     *         function; the API answers {@code 409} and the leftover resources are named in it
     */
    public Optional<FunctionSpec> remove(String name) {
        ensureReady();
        return locks.withLock(name, () -> {
            boolean listenersRetired = applicationState.beginRemoval(name);
            RegisteredFunction existing = registry.detach(name);
            if (existing == null) {
                return Optional.empty();
            }

            List<FunctionRegistrationListener> notified = new ArrayList<>();
            boolean deprovisioned = false;
            try {
                if (!listenersRetired) {
                    for (FunctionRegistrationListener listener : listeners) {
                        listener.onRemove(name);
                        notified.add(listener);
                    }
                }
                if (existing.managedDeploymentTarget().isPresent()) {
                    managedDeploymentCoordinator.deprovision(existing.managedDeploymentTarget().orElseThrow());
                    deprovisioned = true;
                }
                registry.persistCurrentSnapshot(); // durable delete commit happens last
                // A retry that finally emptied the backend ends the pending state.
                applicationState.clearFunction(name);
                return Optional.of(existing.spec());
            } catch (PartialDeprovisionException partial) {
                holdPendingRemoval(existing, partial);
                throw new FunctionRemovalPendingException(name, partial.remainingResources(), partial);
            } catch (RuntimeException failure) {
                rollbackRemoval(existing, notified, deprovisioned, failure);
                throw failure;
            }
        });
    }

    /**
     * Pending removal — the outcome of a partial deprovision, and deliberately not a rollback.
     *
     * <p>The catalog entry is put back, because that entry is what keeps the leftover resources
     * traceable and what makes a retried {@code DELETE} resume the cleanup instead of answering
     * {@code 404}. Everything else stays removed: the registration listeners are <b>not</b>
     * replayed, so capacity, queues and meters stay retired with the generation that owned those
     * resources (I7), and {@link #get} refuses the function outright, so no invocation is admitted
     * into a deployment whose endpoint the backend has already closed.
     *
     * <p>Contrast with {@link #rollbackRemoval}: an operational rollback is only honest when
     * nothing needed was lost — there the deprovision itself succeeded and {@link #reconcile}
     * rebuilt and verified the deployment before the function was declared live again. Here
     * resources are missing, so declaring the function restored would be a lie the next invocation
     * would expose.
     */
    private void holdPendingRemoval(RegisteredFunction existing, PartialDeprovisionException partial) {
        registry.restoreDetachedPendingRemoval(existing, partial.remainingResources());
        try {
            registry.persistCurrentSnapshot(); // best-effort: keep the leftovers visible after a restart
        } catch (RuntimeException persistFailure) {
            partial.addSuppressed(persistFailure);
        }
        log.error("Function '{}' is in pending removal: backend '{}' still owns {}",
                existing.name(), partial.backendId(), partial.remainingResources());
    }

    /**
     * Operational rollback, allowed only because nothing needed was lost. Either the deployment was
     * never touched, or it was fully deprovisioned and {@link #reconcile} has rebuilt it against
     * the persisted backend and replica target — a rebuild that reports the resources back before
     * the function is declared live again. A backend that lost resources reports it instead, and
     * that outcome goes to {@link #holdPendingRemoval}, never here.
     */
    private void rollbackRemoval(RegisteredFunction existing,
                                 List<FunctionRegistrationListener> notified,
                                 boolean deprovisioned,
                                 RuntimeException failure) {
        RegisteredFunction restored = existing;
        if (deprovisioned) {
            try {
                restored = reconcile(existing);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                registry.restoreDetachedUnavailable(existing, rollback.getMessage());
                log.error("Function '{}' remains unavailable after delete rollback reconcile failed",
                        existing.name(), rollback);
                return;
            }
        }
        rollbackRemovalListeners(restored.spec(), notified, failure);
        registry.restoreDetached(restored); // memory-only: keep serving even if the catalog is unwritable
        if (deprovisioned) {
            try {
                // The provider was rebuilt after the durable delete failed; persist any refreshed
                // endpoint/object metadata. If teardown itself failed, the old snapshot never
                // changed and rewriting it would only add another failure point.
                registry.persistCurrentSnapshot();
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
        }
    }

    private RegisteredFunction resolveRegistration(FunctionSpec spec) {
        if (spec.executionMode() != ExecutionMode.DEPLOYMENT) {
            return RegisteredFunction.nonManaged(spec);
        }

        ProvisionResult provisionResult = deploymentProviderResolver.resolveAndProvision(spec, null);
        FunctionSpec effectiveSpec = withEffectiveProvisioning(spec, provisionResult);
        return new RegisteredFunction(
                effectiveSpec,
                new DeploymentMetadata(
                        spec.executionMode(),
                        provisionResult.effectiveExecutionMode(),
                        provisionResult.backendId(),
                        provisionResult.degradationReason(),
                        provisionResult.endpointUrl(),
                        provisionResult.deploymentObjects()
                )
        );
    }

    private FunctionSpec withEffectiveProvisioning(FunctionSpec spec, ProvisionResult provisionResult) {
        return spec.withEndpoint(provisionResult.endpointUrl(), provisionResult.effectiveExecutionMode());
    }

    private void rollbackProvisionedRegistration(RegisteredFunction function, RuntimeException failure) {
        Optional<ManagedDeploymentTarget> target = managedDeploymentTarget(function);
        if (target.isEmpty()) return;
        try {
            managedDeploymentCoordinator.deprovision(target.get());
        } catch (PartialDeprovisionException partial) {
            // The rollback could not undo the provisioning: resources this registration created are
            // still there. Dropping the name would orphan them with nothing left to trace them by,
            // so the function is held in pending removal instead — refused for everything except
            // the delete that resumes the cleanup.
            holdPendingRemoval(function, partial);
            failure.addSuppressed(partial);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Restores a managed deployment that was already deprovisioned before a delete-catalog failure.
     * Reconciles only the exact persisted backend (never backend selection or degradation), driving
     * the provider's non-destructive reconcile toward the persisted spec and replica target.
     */
    private RegisteredFunction reconcile(RegisteredFunction existing) {
        ManagedDeploymentTarget target = existing.managedDeploymentTarget().orElseThrow(() -> new IllegalStateException(
                "Function '" + existing.name() + "' has no persisted managed deployment"));
        ProvisionResult result = managedDeploymentCoordinator.requireProvider(target)
                .reconcile(existing.spec(), existing.desiredReplicas(), existing.deploymentMetadata().deploymentObjects());
        return existing.withProvisionResult(result);
    }

    private ManagedDeploymentTarget requireManagedDeploymentTarget(RegisteredFunction function) {
        return managedDeploymentTarget(function).orElseThrow(() -> new IllegalStateException(
                "Function '" + function.name() + "' is not a managed deployment"));
    }

    private Optional<ManagedDeploymentTarget> managedDeploymentTarget(RegisteredFunction function) {
        if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
            return Optional.empty();
        }
        String backendId = function.deploymentMetadata().deploymentBackend();
        return backendId == null || backendId.isBlank()
                ? Optional.empty()
                : Optional.of(new ManagedDeploymentTarget(function.name(), backendId));
    }

    private void rollbackRegistrationListeners(String functionName,
                                               List<FunctionRegistrationListener> notified,
                                               RuntimeException failure) {
        for (int i = notified.size() - 1; i >= 0; i--) {
            try {
                notified.get(i).onRemove(functionName);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void rollbackRemovalListeners(FunctionSpec spec,
                                          List<FunctionRegistrationListener> notified,
                                          RuntimeException failure) {
        for (int i = notified.size() - 1; i >= 0; i--) {
            try {
                notified.get(i).onRegister(spec);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

}
