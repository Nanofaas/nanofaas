package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Atomic global and per-function accounting for one resource unit.
 *
 * <p>Limits are supplied explicitly: this primitive deliberately chooses no P07e
 * defaults. Every successful acquisition returns the only handle that may release
 * those units. Accounting is additionally split by {@link FunctionGeneration}, so
 * old-generation completion drains its own reservation while the per-function cap
 * still covers all same-name generations that coexist during remove/re-register.
 * New reservations are admitted only while the supplied generation is proven active
 * by the existing {@link FunctionCapacityRegistry} lifecycle authority.
 *
 * <p>Thread-safe. The two limit checks and all counter changes share one critical
 * section, making reservation atomic across the global and per-function budgets.
 */
public final class ResourceQuota {

    private final FunctionCapacityRegistry generationAuthority;
    private volatile Limits limits; // NOSONAR (java:S3077): thread-safe or immutable value replaced wholesale
    private final Object lock = new Object();
    private final Map<String, Long> reservedByFunction = new HashMap<>();
    private final Map<FunctionGeneration, Long> reservedByGeneration = new HashMap<>();
    private long globallyReserved;

    public ResourceQuota(
            FunctionCapacityRegistry generationAuthority,
            long globalLimit,
            long perFunctionLimit) {
        if (globalLimit < 1) {
            throw new IllegalArgumentException("globalLimit must be positive, was " + globalLimit);
        }
        if (perFunctionLimit < 1) {
            throw new IllegalArgumentException(
                    "perFunctionLimit must be positive, was " + perFunctionLimit);
        }
        this.generationAuthority = Objects.requireNonNull(generationAuthority, "generationAuthority");
        this.limits = new Limits(globalLimit, perFunctionLimit);
    }

    /**
     * Reserves {@code units} against both caps, or returns empty without changing
     * accounting when the generation is not active or either cap has insufficient room.
     */
    public Optional<Reservation> tryReserve(
            FunctionGeneration generation, ResourceOwner owner, long units) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(owner, "owner");
        if (units < 1) {
            throw new IllegalArgumentException("units must be positive, was " + units);
        }
        Reservation reservation = generationAuthority.withActiveGeneration(
                generation, () -> reserveActiveGeneration(generation, owner, units));
        return Optional.ofNullable(reservation);
    }

    private Reservation reserveActiveGeneration(
            FunctionGeneration generation, ResourceOwner owner, long units) {
        synchronized (lock) {
            Limits activeLimits = limits;
            long functionReserved = reservedByFunction.getOrDefault(generation.functionName(), 0L);
            if (units > activeLimits.global() - globallyReserved
                    || units > activeLimits.perFunction() - functionReserved) {
                return null;
            }
            globallyReserved += units;
            reservedByFunction.put(generation.functionName(), functionReserved + units);
            reservedByGeneration.merge(generation, units, Long::sum);
            Claim claim = new Claim(generation, units);
            return new Reservation(claim, owner);
        }
    }

    public long reservedGlobally() {
        synchronized (lock) {
            return globallyReserved;
        }
    }

    public long reservedForFunction(String functionName) {
        synchronized (lock) {
            return reservedByFunction.getOrDefault(functionName, 0L);
        }
    }

    public long reservedForGeneration(FunctionGeneration generation) {
        synchronized (lock) {
            return reservedByGeneration.getOrDefault(generation, 0L);
        }
    }

    /** Changes admission ceilings without touching reservations already owned by live work. */
    public void updateLimits(long globalLimit, long perFunctionLimit) {
        Limits replacement = new Limits(globalLimit, perFunctionLimit);
        synchronized (lock) {
            limits = replacement;
        }
    }

    public Limits limits() {
        return limits;
    }

    public record Limits(long global, long perFunction) {
        public Limits {
            if (global < 1 || perFunction < 1) {
                throw new IllegalArgumentException("quota limits must be positive");
            }
            if (perFunction > global) {
                throw new IllegalArgumentException("per-function quota must not exceed global quota");
            }
        }
    }

    private void release(Claim claim) { // NOSONAR (java:S3398): quota bookkeeping under the outer lock; the reservation only delegates
        synchronized (lock) {
            if (claim.active) {
                releaseCurrent(claim);
            }
        }
    }

    private void releaseCurrent(Claim claim) {
        claim.active = false;
        globallyReserved -= claim.units;
        subtractOrRemove(reservedByFunction, claim.generation.functionName(), claim.units);
        subtractOrRemove(reservedByGeneration, claim.generation, claim.units);
    }

    private static <K> void subtractOrRemove(Map<K, Long> reservations, K key, long units) {
        long remaining = reservations.getOrDefault(key, 0L) - units;
        if (remaining == 0) {
            reservations.remove(key);
        } else {
            reservations.put(key, remaining);
        }
    }

    private static final class Claim {
        private final FunctionGeneration generation;
        private final long units;
        private boolean active = true;

        private Claim(FunctionGeneration generation, long units) {
            this.generation = generation;
            this.units = units;
        }
    }

    /** The owner capability for one successful reservation. */
    public final class Reservation implements AutoCloseable {
        private final Claim claim;
        private final ResourceOwner owner;

        private Reservation(Claim claim, ResourceOwner owner) {
            this.claim = claim;
            this.owner = owner;
        }

        public FunctionGeneration generation() {
            return claim.generation;
        }

        public ResourceOwner owner() {
            return owner;
        }

        public long units() {
            return claim.units;
        }

        public boolean isClosed() {
            synchronized (lock) {
                return !claim.active;
            }
        }

        /** Releases this reservation at most once. */
        @Override
        public void close() {
            release(claim);
        }
    }
}
