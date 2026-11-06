package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Replica admission and physical ownership; owns no HTTP server, client or executor. */
public final class ManagedReplicaPool implements ManagedReplicaSet {
    final ReplicaSlots replicaSlots = new ReplicaSlots();
    private final Map<String, String> runtimeIncarnations = new ConcurrentHashMap<>();
    final AtomicBoolean physicalSlots = new AtomicBoolean();
    private final RuntimeExecutionProbe executionProbe;
    private final Executor executor;
    private final Duration probeRetention;
    private final AtomicReference<List<String>> backends = new AtomicReference<>(List.of());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, OrdinarySlot> ordinary = new LinkedHashMap<>();
    private Set<String> readyBackends = Set.of();
    private final Set<String> unverifiedBackends = new HashSet<>();
    private Duration timeout = Duration.ofSeconds(30);
    private final Set<Reservation> waiting = new HashSet<>();
    private int cursor;
    private int inFlight;
    private int maxInFlight = 4;
    private int perReplicaConcurrency = 4;

    public ManagedReplicaPool(RuntimeExecutionProbe executionProbe, Executor executor, Duration probeRetention) {
        this.executionProbe = Objects.requireNonNull(executionProbe);
        this.executor = Objects.requireNonNull(executor);
        this.probeRetention = Objects.requireNonNull(probeRetention);
    }

    @Override public synchronized void updateReadyBackends(List<String> ready) { readyBackends = Set.copyOf(ready); }


    public synchronized ManagedInvocationTarget acquire(String executionId, String attempt) {
        if (closed.get() || inFlight >= maxInFlight) return null;
        ReplicaLease physical = null;
        String backend;
        OrdinarySlot slot = null;
        if (physicalSlots.get()) {
            // A timeout is not permission to send the same execution to another physical slot.
            if (replicaSlots.occupied().stream().anyMatch(lease -> lease.executionId().equals(executionId))) return null;
            for (int i = 0; i < backends.get().size(); i++) {
                physical = replicaSlots.tryAcquire(executionId, attempt).orElse(null);
                if (physical == null || readyBackends.contains(physical.backend()) && !unverifiedBackends.contains(physical.backend())) break;
                physical.cancelBeforeDispatch(); physical = null;
            }
            if (physical == null) {
                if (!replicaSlots.awaitingAllocation()) return null;
                var pending = new Reservation(null, null, null);
                inFlight++;
                waiting.add(pending);
                pending.waiter = new FutureTask<>(() -> {
                    try {
                        var lease = replicaSlots.acquireWhenResumed(executionId, attempt, timeout).orElseThrow();
                        synchronized (ManagedReplicaPool.this) {
                            if (pending.finished || closed.get()) { lease.cancelBeforeDispatch(); return null; }
                            pending.backend = lease.backend(); pending.physical = lease;
                            waiting.remove(pending);
                        }
                        // Completion can enter the coordinator; never run it while holding the pool lock.
                        pending.readiness.complete(null);
                    } catch (Exception failure) {
                        pending.readiness.completeExceptionally(failure);
                    }
                    return null;
                });
                try { executor.execute(pending.waiter); }
                catch (RuntimeException failure) { pending.close(); throw failure; }
                return pending;
            }
            backend = physical.backend();
        } else {
            var candidates = backends.get();
            backend = null;
            for (int i = 0; i < candidates.size(); i++) {
                int index = Math.floorMod(cursor + i, candidates.size());
                String candidate = candidates.get(index);
                var current = ordinary.get(candidate);
                if (current != null && readyBackends.contains(candidate) && !current.draining && current.inFlight < perReplicaConcurrency) {
                    backend = candidate; slot = current; cursor = (index + 1) % candidates.size(); break;
                }
            }
            if (backend == null) return null;
            slot.inFlight++;
        }
        inFlight++;
        return new Reservation(backend, slot, physical);
    }

    @Override public synchronized void updateLimits(int maxInFlight, Duration timeout) {
        updateLimits(maxInFlight, maxInFlight, timeout);
    }

    public synchronized void updateLimits(int maxInFlight, int perReplicaConcurrency, Duration timeout) {
        if (maxInFlight < 1 || perReplicaConcurrency < 1 || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Positive concurrency and timeout required");
        }
        this.maxInFlight = maxInFlight;
        this.perReplicaConcurrency = perReplicaConcurrency;
        this.timeout = timeout;
    }

    @Override public synchronized void close() {
        closed.set(true);
        for (var pending : List.copyOf(waiting)) pending.close();
    }

    private static final class OrdinarySlot { int inFlight; boolean draining; }

    private final class Reservation implements ManagedInvocationTarget {
        private String backend;
        private final OrdinarySlot ordinarySlot;
        private ReplicaLease physical;
        private final CompletableFuture<Void> readiness = new CompletableFuture<>();
        private FutureTask<Void> waiter;
        private boolean finished;
        private boolean dispatched;

        Reservation(String backend, OrdinarySlot ordinarySlot, ReplicaLease physical) {
            this.backend = backend; this.ordinarySlot = ordinarySlot; this.physical = physical;
            if (backend != null) readiness.complete(null);
        }
        @Override public CompletionStage<Void> ready() { return readiness; }
    @Override public String endpointUrl() { return backend + "/invoke"; }
        @Override public String replicaId() { return backend; }
        @Override public boolean markDispatched() {
            synchronized (ManagedReplicaPool.this) {
                if (finished || dispatched || backend == null || closed.get() || !readyBackends.contains(backend)
                        || unverifiedBackends.contains(backend)
                        || (physical == null ? ordinarySlot.draining : !replicaSlots.routable(backend))) return false;
                dispatched = true;
                if (physical != null) physical.markDispatched();
                return true;
            }
        }
        @Override public CompletionStage<Boolean> executionEvidence() {
            if (physical == null) return CompletableFuture.completedFuture(false);
            return CompletableFuture.supplyAsync(() -> {
                var proof = executionProbe.observe(URI.create(backend), physical.executionId());
                boolean executed = physical.hasExecutionEvidence(proof);
                physical.markReleased(proof);
                return executed;
            }, executor);
        }
        @Override public void close() {
            synchronized (ManagedReplicaPool.this) {
                if (finished) return;
                finished = true;
                waiting.remove(this);
                if (waiter != null) waiter.cancel(true);
                readiness.cancel(false);
                inFlight--;
                if (ordinarySlot != null) ordinarySlot.inFlight--;
                if (physical != null && !physical.cancelBeforeDispatch()) {
                    try { executor.execute(() -> observeUntilReleased(physical)); }
                    catch (RejectedExecutionException shutdown) {
                        // Shutdown is not proof of physical release; preserve the lease.
                    }
                }
            }
        }
    }
    @Override
    public synchronized void updateBackends(List<String> backendBaseUrls) {
        var ready = backendBaseUrls == null ? List.<String>of() : List.copyOf(backendBaseUrls);
        if(physicalSlots.get()) {
            var incarnations = new java.util.LinkedHashMap<String,String>();
            var verified = new HashSet<String>();
            for (var backend : ready) {
                String incarnation;
                try {
                    incarnation = executionProbe.eligibleIncarnation(URI.create(backend));
                    unverifiedBackends.add(backend);
                    verified.add(backend);
                } catch (IllegalStateException busyOrUnknown) {
                    // An occupied lease retains its identity; a failed probe cannot release or replace it.
                    incarnation = runtimeIncarnations.get(backend);
                    if (incarnation == null || replicaSlots.occupied().stream().noneMatch(lease -> lease.backend().equals(backend))) {
                        unverifiedBackends.add(backend);
                        throw busyOrUnknown;
                    }
                }
                incarnations.put(backend, incarnation);
            }
            runtimeIncarnations.putAll(incarnations);
            replicaSlots.update(incarnations);
            unverifiedBackends.removeAll(verified);
            var occupiedBackends=replicaSlots.occupied().stream().map(ReplicaLease::backend).collect(java.util.stream.Collectors.toSet());
            runtimeIncarnations.keySet().removeIf(id -> !ready.contains(id) && !occupiedBackends.contains(id));
        }
        ordinary.forEach((id, slot) -> { if (!ready.contains(id)) slot.draining = true; });
        ordinary.entrySet().removeIf(e -> !ready.contains(e.getKey()) && e.getValue().inFlight == 0);
        ready.forEach(id -> ordinary.computeIfAbsent(id, ignored -> new OrdinarySlot()).draining = false);
        unverifiedBackends.retainAll(ready);
        backends.set(ready);
        readyBackends = Set.copyOf(ready);
    }

    @Override
    public synchronized void enablePhysicalSlots() {
        if(physicalSlots.get()) return;
        var incarnations = new java.util.LinkedHashMap<String,String>();
        for(var backend:backends.get()) incarnations.put(backend, executionProbe.eligibleIncarnation(URI.create(backend)));
        runtimeIncarnations.putAll(incarnations); replicaSlots.update(incarnations); physicalSlots.set(true);
    }
    @Override public synchronized boolean backendReady(String backendId) {
        return !unverifiedBackends.contains(backendId) && (!physicalSlots.get() || replicaSlots.routable(backendId));
    }
    @Override public synchronized void beginDrain(String backendId) {
        if (physicalSlots.get()) replicaSlots.beginDrain(backendId);
        var slot = ordinary.get(backendId); if (slot != null) slot.draining = true;
    }
    @Override public boolean awaitDrained(String backendId, Duration timeout) {
        long deadline=System.nanoTime()+timeout.toNanos();
        do {
            for(var lease:replicaSlots.occupied()) if(lease.backend().equals(backendId))
                lease.markReleased(executionProbe.observe(URI.create(lease.backend()),lease.executionId()));
            synchronized (this) {
                var slot = ordinary.get(backendId);
                if (physicalSlots.get() ? replicaSlots.drained(backendId) : slot == null || slot.inFlight == 0) return true;
            }
            try { Thread.sleep(50); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        } while(System.nanoTime()<deadline);
        return false;
    }
    @Override
    public ReplicaSlots.QuiesceToken beginQuiesce() {
        if (closed.get() || !physicalSlots.get()) {
            throw new IllegalStateException("Physical replica pool unavailable");
        }
        return replicaSlots.beginQuiesce();
    }

    @Override
    public boolean awaitQuiesced(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Positive quiesce timeout required");
        }
        if (closed.get() || !physicalSlots.get()) return false;
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            for (var lease : replicaSlots.occupied()) {
                lease.markReleased(executionProbe.observe(URI.create(lease.backend()), lease.executionId()));
            }
            if (replicaSlots.quiesced()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (!closed.get() && System.nanoTime() < deadline);
        return false;
    }

    @Override
    public boolean resume(ReplicaSlots.QuiesceToken token, String admissionEpoch) {
        return !closed.get() && physicalSlots.get() && replicaSlots.resume(token, admissionEpoch);
    }

    @Override
    public boolean resumeRetained(ReplicaSlots.QuiesceToken token, String admissionEpoch) {
        return !closed.get() && physicalSlots.get() && replicaSlots.resumeRetained(token, admissionEpoch);
    }

    ExecutionObservation observe(ReplicaLease lease) {
        return executionProbe.observe(URI.create(lease.backend()), lease.executionId());
    }

    void observeUntilReleased(ReplicaLease lease) {
        long deadline=System.nanoTime()+probeRetention.toNanos();
        while(!closed.get() && System.nanoTime()<deadline) {
            if (!replicaSlots.occupied().contains(lease)) return;
            if(lease.markReleased(executionProbe.observe(URI.create(lease.backend()),lease.executionId()))) return;
            try { Thread.sleep(100); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
        // Uncertain executions remain quarantined. Retention expiry is not evidence of release.
    }

}
