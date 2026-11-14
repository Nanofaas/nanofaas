package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaAdmissionToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("ReferenceEquality") // Proxy identity determines which replaced owner is closed.
public class LocalManagedDeploymentProvider implements ManagedDeploymentProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalManagedDeploymentProvider.class);

    /** What the partial outcome names when the proxy itself could not be released. */
    private static final String DEPLOYMENT_INSTANCE = "invocationTargetInstance";

    private static final String PROXY_RESOURCE = "local invocation proxy";

    public static final String MANAGED_LABEL = "io.nanofaas.managed";
    public static final String FUNCTION_LABEL = "io.nanofaas.function";
    public static final String REPLICA_LABEL = "io.nanofaas.replica";
    private static final Set<String> RESERVED_ENV = Set.of(
            "FUNCTION_NAME", "WARM", "TIMEOUT_MS", "EXECUTION_MODE", "WATCHDOG_CMD", "CALLBACK_URL"
    );

    /**
     * Fallbacks for a spec that was not run through {@code FunctionSpecResolver} (which always
     * fills both). Production specs are resolved before they reach this provider, so these values
     * only guard direct construction in tests and embedded use.
     */
    private static final int DEFAULT_CONCURRENCY = 4;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final ContainerRuntimeAdapter adapter;
    private final String backendId;
    private final LocalDeploymentSettings settings;
    private final EndpointProbe endpointProbe;
    private final ManagedReplicaPoolFactory proxyFactory;
    private final Map<String, FunctionState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LocalManagedDeploymentProvider(String backendId, LocalDeploymentSettings settings,
                                          ContainerRuntimeAdapter adapter, EndpointProbe endpointProbe,
                                          ManagedReplicaPoolFactory proxyFactory) {
        this.backendId = backendId;
        this.settings = settings;
        this.adapter = adapter;
        this.endpointProbe = endpointProbe;
        this.proxyFactory = proxyFactory;
    }

    @Override
    public it.unimib.datai.nanofaas.controlplane.deployment.ManagedTargetResolution acquireInvocationTarget(
            String functionName, Map<String, String> objects, String executionId, String attempt) {
        var unavailable = it.unimib.datai.nanofaas.controlplane.deployment.ManagedTargetResolution.UNAVAILABLE;
        // No lifecycle lock: it is held across container start, readiness waits and health probes,
        // and dispatch to replicas that are already serving must not wait for those. The pool's
        // own monitor fences what matters here (closed, draining, ready), again at markDispatched.
        var state = states.get(functionName);
        if (state == null || state.pendingRemoval || objects == null
                || !state.instance.equals(objects.get(DEPLOYMENT_INSTANCE))) return unavailable;
        var target = state.proxy.acquire(executionId, attempt);
        return target == null ? unavailable
                : it.unimib.datai.nanofaas.controlplane.deployment.ManagedTargetResolution.reserved(target);
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public boolean isAvailable() {
        return adapter.isAvailable();
    }

    @Override
    public boolean supports(FunctionSpec spec) {
        return spec.executionMode() == ExecutionMode.DEPLOYMENT
                && (spec.imagePullSecrets() == null || spec.imagePullSecrets().isEmpty());
    }

    @Override
    public ProvisionResult provision(FunctionSpec spec) {
        ReentrantLock lock = locks.computeIfAbsent(spec.name(), k -> new ReentrantLock());
        lock.lock();
        try {
            FunctionState existing = states.get(spec.name());
            if (existing != null) {
                if (existing.pendingRemoval) {
                    // Its proxy is already closed and some of its containers may still be alive:
                    // handing this endpoint back would publish a dead URL and silently adopt
                    // resources that are still owed a cleanup.
                    throw new IllegalStateException("Function '" + spec.name()
                            + "' has a pending removal on backend '" + backendId()
                            + "'; finish the deprovision before provisioning it again");
                }
                return new ProvisionResult(null, backendId(), deploymentObjects(states.get(spec.name())));
            }

            ManagedReplicaSet proxy = proxyFactory.create(spec.name());
            FunctionState state = new FunctionState(spec, proxy, containerNamePrefix(spec.name()));
            states.put(spec.name(), state);
            try {
                scaleTo(state, desiredReplicas(spec));
                return new ProvisionResult(null, backendId(), deploymentObjects(states.get(spec.name())));
            } catch (RuntimeException e) {
                for (int replicaIndex : List.copyOf(state.replicas.keySet()).reversed()) {
                    suppressCleanupFailure(e, () -> removeReplica(state, replicaIndex));
                }
                states.remove(spec.name());
                suppressCleanupFailure(e, proxy::close);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ProvisionResult reconcile(FunctionSpec spec,
                                     int desiredReplicas,
                                     Map<String, String> deploymentObjects) {
        ReentrantLock lock = locks.computeIfAbsent(spec.name(), k -> new ReentrantLock());
        lock.lock();
        try {
            String prefix = requirePersistedPrefix(spec.name(), deploymentObjects);
            List<ManagedContainer> discovered = adapter.listManagedContainers(containerFunctionLabel(spec.name())).stream()
                    .sorted(Comparator.comparingInt(ManagedContainer::replicaIndex))
                    .toList();
            validateReplicaIndexes(spec.name(), prefix, discovered);
            if(isOneShot(spec)) {
                try(var client=java.net.http.HttpClient.newHttpClient()) {
                    var physicalProbe=new HttpRuntimeExecutionProbe(client,Duration.ofSeconds(2));
                    for(var container:discovered) {
                        if(!adoptable(container)) throw new IllegalStateException("Cannot prove recovered one-shot replica is idle");
                        physicalProbe.eligibleIncarnation(java.net.URI.create(container.baseUrl()));
                    }
                }
            }

            ManagedReplicaSet proxy = proxyFactory.create(spec.name());
            FunctionState state = new FunctionState(spec, proxy, prefix);
            Set<String> createdDuringReconcile = new HashSet<>();
            try {
                for (ManagedContainer container : discovered) {
                    if (container.replicaIndex() > desiredReplicas) {
                        adapter.removeContainer(container.name());
                    } else if (adoptable(container)) {
                        String url = container.baseUrl();
                        endpointProbe.awaitReady(url, settings.readinessTimeout(), settings.readinessPollInterval());
                        state.replicas.put(container.replicaIndex(),
                                new ReplicaState(container.name(), url));
                    } else {
                        adapter.removeContainer(container.name());
                        addReplica(state, container.replicaIndex());
                        createdDuringReconcile.add(container.name());
                    }
                }
                createMissingReplicas(state, desiredReplicas, createdDuringReconcile);
                // Unreachable today (both callers reconcile onto an absent entry), but a leaked
                // proxy now costs an HttpServer, a virtual-thread executor and an HttpClient with
                // its own selector threads — too much to leave to the callers staying that way.
                FunctionState replaced = states.put(spec.name(), state);
                if (replaced != null && replaced.proxy != proxy) {
                    safeClose(replaced.proxy);
                }
                return new ProvisionResult(null, backendId(), deploymentObjects(states.get(spec.name())));
            } catch (RuntimeException failure) {
                for (String createdName : createdDuringReconcile) {
                    suppressCleanupFailure(failure, () -> adapter.removeContainer(createdName));
                }
                safeClose(proxy);
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void updateSpec(FunctionSpec spec) {
        ReentrantLock lock = locks.get(spec.name());
        if (lock == null) {
            return;
        }
        lock.lock();
        try {
            FunctionState state = states.get(spec.name());
            if (state == null) {
                return;
            }
            state.spec = spec;
            pushProxyConfig(state);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes every resource this backend owns for the function and reports what it could not
     * remove, instead of dropping the only handle on it (invariant I10).
     *
     * <p><b>Order, and what it commits to.</b> The proxy is released first: the removal decision is
     * already taken, so the function must stop accepting invocations before its replicas start
     * disappearing underneath it. Closing it is irreversible, and that is precisely why every
     * failure from here on is reported as a pending removal and never as an operational rollback —
     * the endpoint is gone whatever happens to the containers. Its release is attempted whenever
     * this process owns one, and a failure to release it is reported like any other leftover rather
     * than swallowed.
     *
     * <p><b>Every resource is attempted.</b> Containers come from two sources merged into one pass:
     * the replicas this process tracks, and whatever the runtime still reports under this
     * function's managed labels — which is how a restart, or a container whose tracking was lost
     * while it was being created, is still found. One failure never skips the others; each error is
     * collected and travels with the partial outcome.
     *
     * <p><b>Ownership is given up last.</b> The tracked state is dropped only once nothing is left.
     * While resources remain, that entry is what keeps them traceable, and a second
     * {@code deprovision} resumes from it — or, after a restart, from the labels alone.
     *
     * @throws PartialDeprovisionException if any resource could not be released
     */
    @Override
    public void deprovision(String functionName) {
        ReentrantLock lock = locks.computeIfAbsent(functionName, k -> new ReentrantLock());
        boolean fullyRemoved = false;
        lock.lock();
        try {
            fullyRemoved = releaseAllResources(functionName);
        } finally {
            lock.unlock();
            if (fullyRemoved) {
                locks.remove(functionName);
            }
        }
    }

    /**
     * Lets invocations already sent to a replica finish before its container is force-removed,
     * as a scale-down does. Bounded by the function's own timeout: past it the dispatcher has
     * given up on the invocation anyway, so removal never waits on a stuck one.
     */
    private void drainBeforeRemoval(FunctionState state) {
        state.replicas.values().forEach(replica -> state.proxy.beginDrain(replica.baseUrl()));
        long timeoutMs = state.spec.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : state.spec.timeoutMs();
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
        for (var replica : state.replicas.values()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !state.proxy.awaitDrained(replica.baseUrl(), Duration.ofNanos(remaining))) {
                log.warn("Removing function '{}' with invocations still in flight after {} ms",
                        state.spec.name(), timeoutMs);
                return;
            }
        }
    }

    /**
     * @return {@code true} when nothing is left for this function
     * @throws PartialDeprovisionException when something is
     */
    private boolean releaseAllResources(String functionName) {
        FunctionState state = states.get(functionName);
        List<Throwable> failures = new ArrayList<>();
        List<String> remaining = new ArrayList<>();

        if (state != null) {
            // Marked before anything is touched: from here the state exists only to be cleaned up.
            state.pendingRemoval = true;
            if(isOneShot(state.spec)) {
                state.replicas.values().forEach(replica -> state.proxy.beginDrain(replica.baseUrl()));
                for(var replica:state.replicas.values()) {
                    if(!state.proxy.awaitDrained(replica.baseUrl(),settings.readinessTimeout()))
                        throw new PartialDeprovisionException(functionName,backendId(),List.of(replica.containerName()),
                            List.of(new IllegalStateException("Physical drain not proven")));
                }
            } else {
                drainBeforeRemoval(state);
            }
            try {
                state.proxy.close();
            } catch (RuntimeException proxyFailure) {
                failures.add(proxyFailure);
                remaining.add(PROXY_RESOURCE);
            }
        }

        for (String containerName : containersToRemove(functionName, state, failures, remaining)) {
            try {
                adapter.removeContainer(containerName);
                forgetReplica(state, containerName);
            } catch (RuntimeException removalFailure) {
                failures.add(removalFailure);
                remaining.add(containerName);
            }
        }

        if (failures.isEmpty()) {
            states.remove(functionName);
            return true;
        }
        log.error("Partial deprovision of function '{}': {} resource(s) still owned by backend '{}': {}",
                functionName, remaining.size(), backendId(), remaining);
        throw new PartialDeprovisionException(functionName, backendId(), remaining, failures);
    }

    /**
     * The tracked replicas (highest index first, as scaling down does) followed by anything else the
     * runtime still reports for this function. Discovery is what makes the cleanup resumable across
     * a restart; a discovery failure is collected rather than thrown, so the containers this process
     * does know about are still removed.
     */
    private Set<String> containersToRemove(String functionName,
                                           FunctionState state,
                                           List<Throwable> failures,
                                           List<String> remaining) {
        Set<String> containerNames = new LinkedHashSet<>();
        if (state != null) {
            for (int replicaIndex : List.copyOf(state.replicas.keySet()).reversed()) {
                containerNames.add(state.replicas.get(replicaIndex).containerName());
            }
        }
        try {
            adapter.listManagedContainers(containerFunctionLabel(functionName))
                    .forEach(container -> containerNames.add(container.name()));
        } catch (RuntimeException discoveryFailure) {
            failures.add(discoveryFailure);
            remaining.add("managed containers of '" + functionName + "' (could not be listed)");
        }
        return containerNames;
    }

    /** Drops a replica from the tracked state only once its container is confirmed gone. */
    private static void forgetReplica(FunctionState state, String containerName) {
        if (state == null) {
            return;
        }
        state.replicas.entrySet()
                .removeIf(replica -> replica.getValue().containerName().equals(containerName));
    }

    /**
     * Closes the Java-side resources this provider owns — one HTTP server, virtual-thread executor
     * and HTTP client per function proxy — when the context shuts down.
     *
     * <p>It deliberately removes no container. Containers outlive the process and are recovered on
     * the next start from their managed labels, through {@link #reconcile} or a retried
     * {@link #deprovision}. Closing the context is not deleting the deployment (invariant I10).
     */
    @Override
    public void close() {
        for (FunctionState state : states.values()) {
            safeClose(state.proxy);
        }
    }

    @Override public boolean supportsPhysicalReplicaControl(String functionName) {
        var state=states.get(functionName); return state!=null && !state.pendingRemoval && isOneShot(state.spec);
    }

    @Override
    public boolean supportsEpochReplicaControl(String functionName) {
        return supportsPhysicalReplicaControl(functionName);
    }

    @Override
    public java.util.Optional<ReplicaAdmissionToken> pauseReplicaAdmission(String functionName) {
        var lock = locks.get(functionName);
        if (lock == null) return java.util.Optional.empty();
        lock.lock();
        try {
            var state = states.get(functionName);
            if (state == null || state.pendingRemoval || !isOneShot(state.spec)) return java.util.Optional.empty();
            state.quiesceToken = state.proxy.beginQuiesce();
            return java.util.Optional.of(new ReplicaAdmissionToken(state.quiesceToken.admissionEpoch()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean resumeRetainedReplicas(String functionName, ReplicaAdmissionToken token, String admissionEpoch) {
        var lock = locks.get(functionName);
        if (lock == null) return false;
        lock.lock();
        try {
            var state = states.get(functionName);
            if (state == null || state.pendingRemoval || !isOneShot(state.spec) || token == null
                    || state.quiesceToken == null || !state.quiesceToken.admissionEpoch().equals(token.admissionEpoch())) return false;
            boolean resumed = state.proxy.resumeRetained(state.quiesceToken, admissionEpoch);
            if (resumed) state.quiesceToken = null;
            return resumed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setReplicas(String functionName, int replicas) {
        ReentrantLock lock = locks.get(functionName);
        if (lock == null) {
            return;
        }
        lock.lock();
        try {
            FunctionState state = states.get(functionName);
            if (state == null) {
                return;
            }
            if (state.pendingRemoval) {
                // Scaling a deployment that is being torn down would create containers the pending
                // cleanup then has to delete again, behind a proxy that is already closed.
                log.warn("Ignoring setReplicas({}) for function '{}': its removal is pending", replicas, functionName);
                return;
            }
            scaleTo(state, Math.max(0, replicas));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int getReadyReplicas(String functionName) {
        return getReplicaStatus(functionName).readyReplicas();
    }

    @Override
    public ReplicaStatus getReplicaStatus(String functionName) {
        ReentrantLock lock = locks.get(functionName);
        FunctionState state = states.get(functionName);
        if (lock == null || state == null) {
            return new ReplicaStatus(0, 0);
        }
        // The health probes are HTTP calls and run without the lifecycle lock: a status read
        // must not queue behind a scale-up, nor make dispatch-adjacent work queue behind it.
        List<ReplicaState> observed = List.copyOf(state.replicas.values());
        var ready = observed.stream()
                .filter(replica -> endpointProbe.isReady(replica.baseUrl()))
                .map(ReplicaState::baseUrl).toList();
        // Publish readiness only if nothing changed while probing. A lifecycle operation in
        // progress, or one that finished meanwhile, publishes its own; this older view would hide
        // a replica it never saw.
        if (lock.tryLock()) {
            try {
                if (states.get(functionName) == state && observed.equals(List.copyOf(state.replicas.values()))) {
                    state.proxy.updateReadyBackends(ready);
                }
            } finally {
                lock.unlock();
            }
        }
        int admissible = (int) ready.stream().filter(backend -> !isOneShot(state.spec) || state.proxy.backendReady(backend)).count();
        return new ReplicaStatus(observed.size(), admissible);
    }

    private void scaleTo(FunctionState state, int desiredReplicas) {
        int currentReplicas = state.replicas.size();
        try {
            if (desiredReplicas > currentReplicas) {
                addReplicas(state, currentReplicas + 1, desiredReplicas);
            } else if (desiredReplicas < currentReplicas) {
                for (int replicaIndex = currentReplicas; replicaIndex > desiredReplicas; replicaIndex--) {
                    removeReplica(state, replicaIndex);
                }
            }
        } catch (RuntimeException failure) {
            suppressCleanupFailure(failure, () -> pushProxyConfig(state));
            throw failure;
        }
        pushProxyConfig(state);
    }

    /**
     * Publishes the current replica set and the derived proxy tuning to the proxy. The admission
     * bound tracks the platform's per-replica concurrency ceiling ({@code spec.concurrency})
     * scaled by the number of replicas, so governed traffic is never rejected while runaway bursts
     * beyond that ceiling still get a defined rejection at the proxy. The single-hop timeout is the
     * function's own timeout: this hop is where the function actually runs, so it must not be cut
     * short by a smaller fixed value. Health checks are not governed by this bound.
     */
    private void pushProxyConfig(FunctionState state) {
        if(isOneShot(state.spec)) {
            if((state.spec.concurrency()==null || state.spec.concurrency()<1) || !"1".equals(state.spec.env().get("NANOFAAS_MAX_CONCURRENT_HANDLERS")))
                throw new IllegalArgumentException("One-shot requires a positive function concurrency cap and NANOFAAS_MAX_CONCURRENT_HANDLERS=1");
            state.proxy.enablePhysicalSlots();
        }
        state.proxy.updateBackends(state.replicas.values().stream().map(ReplicaState::baseUrl).toList());
        int perReplicaConcurrency = isOneShot(state.spec) ? 1 : state.spec.concurrency() == null
                ? DEFAULT_CONCURRENCY
                : Math.max(1, state.spec.concurrency());
        int maxInFlight = Math.max(1, state.replicas.size() * perReplicaConcurrency);
        long timeoutMs = state.spec.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : state.spec.timeoutMs();
        state.proxy.updateLimits(maxInFlight, perReplicaConcurrency, Duration.ofMillis(timeoutMs));
    }

    /**
     * Adds replicas {@code first..last}, all at once: each replica starts its container and waits
     * for its own readiness concurrently with the others. Started one after another, a scale-up by
     * N paid N container starts and N function boots end to end.
     *
     * <p>Replica indexes must stay contiguous (the current count is the next index minus one), so
     * only the ready prefix is kept, in index order: a replica that came up after one that did not
     * is removed again, exactly as if it had never been started. A replica whose container never
     * started has nothing to remove ({@link #startReplica} already cleaned up after itself).
     */
    private void addReplicas(FunctionState state, int first, int last) {
        record Launch(ReplicaState replica, RuntimeException failure) {}
        List<java.util.concurrent.Future<Launch>> launches = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int replicaIndex = first; replicaIndex <= last; replicaIndex++) {
                int index = replicaIndex;
                launches.add(executor.submit(() -> {
                    ReplicaState replica;
                    try {
                        replica = startReplica(state, index);
                    } catch (RuntimeException startFailure) {
                        return new Launch(null, startFailure);
                    }
                    try {
                        endpointProbe.awaitReady(replica.baseUrl(), settings.readinessTimeout(), settings.readinessPollInterval());
                        return new Launch(replica, null);
                    } catch (RuntimeException notReady) {
                        return new Launch(replica, notReady);
                    }
                }));
            }
        }
        RuntimeException firstFailure = null;
        for (int offset = 0; offset < launches.size(); offset++) {
            Launch launch = launchOutcome(launches.get(offset));
            if (launch.failure() == null && firstFailure == null) {
                state.replicas.put(first + offset, launch.replica());
                continue;
            }
            if (firstFailure == null) firstFailure = launch.failure();
            if (launch.replica() != null) {
                ReplicaState started = launch.replica();
                suppressCleanupFailure(firstFailure, () -> adapter.removeContainer(started.containerName()));
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private static <T> T launchOutcome(java.util.concurrent.Future<T> launch) {
        try {
            return launch.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while adding replicas", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            // Every launch catches its own failures; this is an Error escaping one of them.
            throw failed.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(failed.getCause());
        }
    }

    // ponytail: reconcile (control-plane restart) still adds missing replicas one at a time.
    private void addReplica(FunctionState state, int replicaIndex) {
        ReplicaState replica = startReplica(state, replicaIndex);
        try {
            endpointProbe.awaitReady(replica.baseUrl(), settings.readinessTimeout(), settings.readinessPollInterval());
        } catch (RuntimeException e) {
            suppressCleanupFailure(e, () -> adapter.removeContainer(replica.containerName()));
            throw e;
        }
        state.replicas.put(replicaIndex, replica);
    }

    /** Starts one replica's container without tracking it; the caller tracks it once it is ready. */
    private ReplicaState startReplica(FunctionState state, int replicaIndex) {
        String containerName = stateContainerName(state, replicaIndex);
        ContainerInstanceSpec instanceSpec = new ContainerInstanceSpec(
                containerName,
                state.spec.image(),
                state.spec.command() == null ? List.of() : state.spec.command(),
                buildEnv(state.spec),
                state.spec.resources(),
                Map.of(
                        MANAGED_LABEL, "true",
                        FUNCTION_LABEL, containerFunctionLabel(state.spec.name()),
                        REPLICA_LABEL, Integer.toString(replicaIndex))
        );
        try {
            return new ReplicaState(containerName, adapter.runContainer(instanceSpec).baseUrl());
        } catch (RuntimeException e) {
            if (!(e instanceof ContainerNameConflictException)) {
                suppressCleanupFailure(e, () -> {
                    boolean owned = adapter.listManagedContainers(containerFunctionLabel(state.spec.name())).stream()
                            .anyMatch(container -> container.name().equals(containerName));
                    if (owned) adapter.removeContainer(containerName);
                });
            }
            throw e;
        }
    }

    /**
     * Removes one replica's container and only then stops tracking it. Dropping the entry first
     * would leave a container alive with nothing left pointing at it whenever the removal fails
     * (R6): the map is the provider's only in-process handle on it.
     */
    private void removeReplica(FunctionState state, int replicaIndex) {
        ReplicaState replica = state.replicas.get(replicaIndex);
        if (replica == null) {
            return;
        }
        if(isOneShot(state.spec) || state.proxy instanceof ManagedReplicaPool) {
            state.proxy.beginDrain(replica.baseUrl());
            if(!state.proxy.awaitDrained(replica.baseUrl(),settings.readinessTimeout()))
                throw new IllegalStateException("Replica still physically occupied or quarantined: "+replica.containerName());
        }
        adapter.removeContainer(replica.containerName());
        state.replicas.remove(replicaIndex);
    }

    private String requirePersistedPrefix(String functionName, Map<String, String> deploymentObjects) {
        String expected = containerNamePrefix(functionName);
        String persisted = deploymentObjects == null ? null
                : deploymentObjects.get(ProvisionResult.CONTAINER_NAME_PREFIX);
        if (persisted == null || persisted.isBlank() || (!expected.equals(persisted) && !legacyContainerNamePrefix(functionName).equals(persisted))) {
            throw new IllegalArgumentException("Persisted container name prefix '" + persisted
                    + "' does not match function '" + functionName + "'");
        }
        return persisted;
    }

    private void validateReplicaIndexes(String functionName, String prefix, List<ManagedContainer> discovered) {
        Set<Integer> seen = new HashSet<>();
        for (ManagedContainer container : discovered) {
            if (container.replicaIndex() < 1) {
                throw new IllegalArgumentException("Managed container '" + container.name()
                        + "' has an invalid replica index");
            }
            String expectedName = prefix.equals(containerNamePrefix(functionName))
                    ? containerName(functionName, container.replicaIndex()) : prefix + "-r" + container.replicaIndex();
            if (!container.name().equals(expectedName)) {
                throw new IllegalArgumentException("Managed container '" + container.name()
                        + "' does not match the persisted prefix '" + prefix + "'");
            }
            if (!seen.add(container.replicaIndex())) {
                throw new IllegalArgumentException("Duplicate replica index " + container.replicaIndex());
            }
        }
    }

    private boolean adoptable(ManagedContainer container) {
        if (!container.running()) {
            return false;
        }
        return container.baseUrl() != null && !container.baseUrl().isBlank();
    }

    private void createMissingReplicas(FunctionState state, int desiredReplicas, Set<String> createdDuringReconcile) {
        for (int replicaIndex = 1; replicaIndex <= desiredReplicas; replicaIndex++) {
            if (!state.replicas.containsKey(replicaIndex)) {
                addReplica(state, replicaIndex);
                createdDuringReconcile.add(stateContainerName(state, replicaIndex));
            }
        }
        pushProxyConfig(state);
    }

    private int desiredReplicas(FunctionSpec spec) {
        ScalingConfig scalingConfig = spec.scalingConfig();
        if (scalingConfig == null || scalingConfig.minReplicas() == null) {
            return 1;
        }
        return Math.max(0, scalingConfig.minReplicas());
    }

    private static boolean isOneShot(FunctionSpec spec) {
        return spec.env()!=null && "true".equals(spec.env().get("NANOFAAS_ONE_SHOT_PROFILE"));
    }

    private Map<String, String> buildEnv(FunctionSpec spec) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("FUNCTION_NAME", spec.name());
        env.put("WARM", "true");
        env.put("TIMEOUT_MS", String.valueOf(spec.timeoutMs()));

        if (spec.runtimeMode() != null) {
            env.put("EXECUTION_MODE", spec.runtimeMode().name());
        }
        if (spec.runtimeCommand() != null && !spec.runtimeCommand().isBlank()) {
            env.put("WATCHDOG_CMD", spec.runtimeCommand());
        }
        if (settings.callbackUrl() != null && !settings.callbackUrl().isBlank()) {
            env.put("CALLBACK_URL", settings.callbackUrl());
        }
        if (spec.env() != null) {
            spec.env().forEach((key, value) -> {
                if (!RESERVED_ENV.contains(key) && value != null) {
                    env.put(key, value);
                }
            });
        }
        return env;
    }

    private Map<String, String> deploymentObjects(FunctionState state) {
        return Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, state.prefix, DEPLOYMENT_INSTANCE, state.instance);
    }

    private String stateContainerName(FunctionState state, int replicaIndex) {
        return state.prefix.equals(containerNamePrefix(state.spec.name()))
                ? containerName(state.spec.name(), replicaIndex) : state.prefix + "-r" + replicaIndex;
    }

    protected String legacyContainerNamePrefix(String functionName) {
        return containerNamePrefix(functionName);
    }

    protected String containerFunctionLabel(String functionName) { return functionName; }

    protected String containerNamePrefix(String functionName) {
        return "nanofaas-" + normalizeName(functionName);
    }

    public static int replicaIndex(String containerName) {
        if (containerName == null) {
            return -1;
        }
        int separator = containerName.lastIndexOf("-r");
        if (separator < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(containerName.substring(separator + 2));
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    protected String containerName(String functionName, int replicaIndex) {
        return containerNamePrefix(functionName) + "-r" + replicaIndex;
    }

    private static String normalizeName(String functionName) {
        String normalized = functionName == null ? "fn" : functionName.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^(?>-+)", "");
        normalized = stripTrailingDashes(normalized);
        return normalized.isBlank() ? "fn" : normalized;
    }

    private static String stripTrailingDashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(0, end);
    }

    private static void safeClose(ManagedReplicaSet proxy) {
        try {
            proxy.close();
        } catch (RuntimeException _) {
            // Best-effort cleanup.
        }
    }

    private static void suppressCleanupFailure(RuntimeException provisioningFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            provisioningFailure.addSuppressed(cleanupFailure);
        }
    }

    private static final class FunctionState {
        // Replaced on a spec update: the proxy's timeout and admission bound are derived from it,
        // so a snapshot frozen at provisioning time would outlive every later PATCH.
        private volatile FunctionSpec spec; // NOSONAR (java:S3077): thread-safe or immutable value replaced wholesale
        private final ManagedReplicaSet proxy;
        private ReplicaSlots.QuiesceToken quiesceToken;
        private final String prefix;
        private final String instance = java.util.UUID.randomUUID().toString();
        // Written under the lifecycle lock; a status read iterates it without that lock.
        private final java.util.concurrent.ConcurrentSkipListMap<Integer, ReplicaState> replicas =
                new java.util.concurrent.ConcurrentSkipListMap<>();
        /**
         * Set by a deprovision that could not finish. The state then exists only to be cleaned up:
         * its proxy is closed, whatever replicas are still listed are the ones that survived the
         * removal, and the entry stays until a retry finally empties it.
         */
        private volatile boolean pendingRemoval;

        private FunctionState(FunctionSpec spec, ManagedReplicaSet proxy, String prefix) {
            this.spec = spec;
            this.proxy = proxy;
            this.prefix = prefix;
        }
    }

    private record ReplicaState(String containerName, String baseUrl) {
    }
}
