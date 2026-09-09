package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finite global and per-function admission authority for caller waiters.
 *
 * <p>One retained entry is published only after its quota reservation succeeds. The
 * returned handle is the sole detach capability and releases both the retained entry
 * and reservation exactly once. Closing this owner fences admission atomically and
 * drains every published waiter, including shutdown races.
 */
public final class WaiterCapacity implements AutoCloseable {
    private final FunctionCapacityRegistry generations;
    private final ResourceQuota quota;
    private final AtomicLong nextId = new AtomicLong();
    private final Object lock = new Object();
    private final Map<Long, Waiter> retained = new LinkedHashMap<>();
    private boolean closed;

    public WaiterCapacity(FunctionCapacityRegistry generations, long globalLimit, long perFunctionLimit) {
        this.generations = Objects.requireNonNull(generations, "generations");
        quota = new ResourceQuota(
                generations, globalLimit, perFunctionLimit);
    }

    /** Reserves against the currently active generation, used for archived replay delivery. */
    public Waiter reserve(String functionName, String executionId) {
        FunctionGeneration generation = generations.activeGeneration(functionName);
        if (generation == null) {
            throw quotaExceeded();
        }
        return reserve(generation, executionId);
    }

    /** Reserves and publishes one waiter, or fails without retaining any waiter state. */
    public Waiter reserve(FunctionGeneration generation, String executionId) {
        Objects.requireNonNull(generation, "generation");
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        synchronized (lock) {
            if (closed) {
                throw quotaExceeded();
            }
            long id = nextId.incrementAndGet();
            ResourceQuota.Reservation reservation = quota.tryReserve(
                            generation,
                            new ResourceOwner(ResourceOwner.Scope.WAITER, executionId + "/waiter-" + id),
                            1)
                    .orElseThrow(WaiterCapacity::quotaExceeded);
            Waiter waiter = new Waiter(id, reservation);
            retained.put(id, waiter);
            return waiter;
        }
    }

    public long reservedGlobally() {
        return quota.reservedGlobally();
    }

    public long reservedForFunction(String functionName) {
        return quota.reservedForFunction(functionName);
    }

    public long reservedForGeneration(FunctionGeneration generation) {
        return quota.reservedForGeneration(generation);
    }

    public int retainedWaiters() {
        synchronized (lock) {
            return retained.size();
        }
    }

    public ResourceQuota.Limits limits() {
        return quota.limits();
    }

    public void updateLimits(long globalLimit, long perFunctionLimit) {
        quota.updateLimits(globalLimit, perFunctionLimit);
    }

    @Override
    public void close() {
        ArrayList<Waiter> draining;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            draining = new ArrayList<>(retained.values());
        }
        draining.forEach(Waiter::close);
    }

    private static InvocationQuotaExceededException quotaExceeded() {
        return new InvocationQuotaExceededException(InvocationQuotaExceededException.Resource.WAITER);
    }

    /** One attached caller's idempotent detach capability. */
    public final class Waiter implements AutoCloseable {
        private final long id;
        private final ResourceQuota.Reservation reservation;
        private boolean detached;

        private Waiter(long id, ResourceQuota.Reservation reservation) {
            this.id = id;
            this.reservation = reservation;
        }

        public FunctionGeneration generation() {
            return reservation.generation();
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (detached) {
                    return;
                }
                detached = true;
                retained.remove(id, this);
            }
            reservation.close();
        }
    }
}
