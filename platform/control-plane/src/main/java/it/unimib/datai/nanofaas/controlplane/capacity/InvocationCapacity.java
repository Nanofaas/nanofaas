package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.Objects;

/** Core aggregate admission authority for logical executions and retained input bytes. */
public final class InvocationCapacity {
    private final FunctionCapacityRegistry generations;
    private final ResourceQuota executions;
    private final ResourceQuota canonicalInputs;
    private final ResourceQuota physicalInputCopies;
    private final int maxInputReferences;

    public InvocationCapacity(
            FunctionCapacityRegistry generations,
            long globalExecutions,
            long perFunctionExecutions,
            long globalInputBytes,
            long perFunctionInputBytes,
            int maxInputReferences) {
        this(generations,
                new ResourceQuota(generations, globalExecutions, perFunctionExecutions),
                new ResourceQuota(generations, globalInputBytes, perFunctionInputBytes),
                new ResourceQuota(generations, globalInputBytes, perFunctionInputBytes),
                maxInputReferences);
    }

    public InvocationCapacity(
            FunctionCapacityRegistry generations,
            long globalExecutions, long perFunctionExecutions,
            long globalCanonicalInputBytes, long perFunctionCanonicalInputBytes,
            long globalPhysicalInputCopyBytes, long perFunctionPhysicalInputCopyBytes,
            int maxInputReferences) {
        this(generations,
                new ResourceQuota(generations, globalExecutions, perFunctionExecutions),
                new ResourceQuota(generations, globalCanonicalInputBytes, perFunctionCanonicalInputBytes),
                new ResourceQuota(generations, globalPhysicalInputCopyBytes, perFunctionPhysicalInputCopyBytes),
                maxInputReferences);
    }

    InvocationCapacity(
            FunctionCapacityRegistry generations,
            ResourceQuota executions,
            ResourceQuota inputs,
            int maxInputReferences) {
        this(generations, executions, inputs, inputs, maxInputReferences);
    }

    InvocationCapacity(
            FunctionCapacityRegistry generations,
            ResourceQuota executions,
            ResourceQuota canonicalInputs,
            ResourceQuota physicalInputCopies,
            int maxInputReferences) {
        this.generations = Objects.requireNonNull(generations, "generations");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.canonicalInputs = Objects.requireNonNull(canonicalInputs, "canonicalInputs");
        this.physicalInputCopies = Objects.requireNonNull(physicalInputCopies, "physicalInputCopies");
        if (maxInputReferences < 1) {
            throw new IllegalArgumentException("maxInputReferences must be positive");
        }
        this.maxInputReferences = maxInputReferences;
    }

    public Admission reserve(String functionName, String executionId, long inputBytes) {
        FunctionGeneration generation = generations.activeGeneration(functionName);
        if (generation == null) {
            throw new InvocationQuotaExceededException(
                    InvocationQuotaExceededException.Resource.EXECUTION);
        }
        ReservationBatch batch = new ReservationBatch();
        try {
            ResourceQuota.Reservation logical = batch.tryReserve(
                            executions,
                            generation,
                            new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, executionId),
                            1)
                    .orElseThrow(() -> new InvocationQuotaExceededException(
                            InvocationQuotaExceededException.Resource.EXECUTION));
            ResourceQuota.Reservation input = batch.tryReserve(
                            canonicalInputs,
                            generation,
                            new ResourceOwner(ResourceOwner.Scope.CANONICAL_INPUT, executionId + "/canonical"),
                            inputBytes)
                    .orElseThrow(() -> new InvocationQuotaExceededException(
                            InvocationQuotaExceededException.Resource.INPUT));
            return new Admission(batch, logical, new RetainedInputLease(input, maxInputReferences));
        } catch (RuntimeException | Error failure) {
            batch.close();
            throw failure;
        }
    }

    /** Compatibility-only registration for factories constructed outside the application context. */
    public void ensureStandaloneGeneration(String functionName, int concurrency) {
        if (generations.activeGeneration(functionName) == null) {
            generations.register(functionName, concurrency);
        }
    }

    /** Reserves an actual additional representation before it is allocated or published. */
    public ResourceQuota.Reservation reserveInputCopy(
            FunctionGeneration generation, ResourceOwner owner, long bytes) {
        return physicalInputCopies.tryReserve(generation, owner, bytes)
                .orElseThrow(() -> new InvocationQuotaExceededException(
                        InvocationQuotaExceededException.Resource.INPUT));
    }

    public long executionReservedGlobally() {
        return executions.reservedGlobally();
    }

    public long executionReservedForFunction(String functionName) {
        return executions.reservedForFunction(functionName);
    }

    public long inputReservedGlobally() {
        return canonicalInputs.reservedGlobally();
    }

    public long inputReservedForFunction(String functionName) {
        return canonicalInputs.reservedForFunction(functionName);
    }

    public long physicalInputCopyReservedGlobally() {
        return physicalInputCopies.reservedGlobally();
    }

    public long physicalInputCopyReservedForFunction(String functionName) {
        return physicalInputCopies.reservedForFunction(functionName);
    }

    public Limits limits() {
        return new Limits(executions.limits(), canonicalInputs.limits(), physicalInputCopies.limits());
    }

    public void updateLimits(Limits replacement) {
        Objects.requireNonNull(replacement, "replacement");
        executions.updateLimits(replacement.executions().global(), replacement.executions().perFunction());
        canonicalInputs.updateLimits(
                replacement.canonicalInputBytes().global(), replacement.canonicalInputBytes().perFunction());
        physicalInputCopies.updateLimits(
                replacement.physicalInputCopyBytes().global(), replacement.physicalInputCopyBytes().perFunction());
    }

    public record Limits(ResourceQuota.Limits executions,
                         ResourceQuota.Limits canonicalInputBytes,
                         ResourceQuota.Limits physicalInputCopyBytes) {
        public Limits {
            Objects.requireNonNull(executions, "executions");
            Objects.requireNonNull(canonicalInputBytes, "canonicalInputBytes");
            Objects.requireNonNull(physicalInputCopyBytes, "physicalInputCopyBytes");
        }
    }

    public static final class Admission {
        private final ReservationBatch batch;
        private final ResourceQuota.Reservation logicalExecution;
        private final RetainedInputLease canonicalInput;
        private boolean published;
        private boolean rolledBack;

        private Admission(
                ReservationBatch batch,
                ResourceQuota.Reservation logicalExecution,
                RetainedInputLease canonicalInput) {
            this.batch = batch;
            this.logicalExecution = logicalExecution;
            this.canonicalInput = canonicalInput;
        }

        public synchronized void publish() {
            if (rolledBack) throw new IllegalStateException("admission was rolled back");
            if (published) return;
            batch.commit();
            published = true;
        }

        public void rollback() {
            boolean releasePublished;
            synchronized (this) {
                if (rolledBack) return;
                rolledBack = true;
                releasePublished = published;
            }
            if (releasePublished) {
                logicalExecution.close();
                canonicalInput.close();
            } else {
                batch.close();
            }
        }

        public ResourceQuota.Reservation logicalExecution() {
            return logicalExecution;
        }

        public RetainedInputLease canonicalInput() {
            return canonicalInput;
        }
    }
}
