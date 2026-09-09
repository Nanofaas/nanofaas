package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.Objects;

/** Core aggregate admission authority for logical executions and retained input bytes. */
public final class InvocationCapacity {
    private final FunctionCapacityRegistry generations;
    private final ResourceQuota executions;
    private final ResourceQuota inputs;
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
                maxInputReferences);
    }

    InvocationCapacity(
            FunctionCapacityRegistry generations,
            ResourceQuota executions,
            ResourceQuota inputs,
            int maxInputReferences) {
        this.generations = Objects.requireNonNull(generations, "generations");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
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
                            inputs,
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
        return inputs.tryReserve(generation, owner, bytes)
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
        return inputs.reservedGlobally();
    }

    public long inputReservedForFunction(String functionName) {
        return inputs.reservedForFunction(functionName);
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
