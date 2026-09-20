package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.QueuedInputLease;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceOwner;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota;
import it.unimib.datai.nanofaas.controlplane.capacity.RetainedInputLease;
import it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/** Capacity ownership attached to one logical execution without duplicating generation identity. */
final class ExecutionInputResources {
    private final InvocationCapacity capacity;
    private final InvocationCapacity.Admission admission;
    private final CanonicalInvocationInput.Accepted canonical;

    ExecutionInputResources(
            InvocationCapacity capacity,
            InvocationCapacity.Admission admission,
            CanonicalInvocationInput.Accepted canonical) {
        this.capacity = capacity;
        this.admission = admission;
        this.canonical = canonical;
    }

    void publish() {
        admission.publish();
    }

    void rollback() {
        admission.rollback();
    }

    void settleLogicalExecution() {
        admission.logicalExecution().close();
        admission.canonicalInput().close();
    }

    FunctionGeneration generation() {
        return admission.canonicalInput().generation();
    }

    QueuedInputLease retainForQueue(InvocationTask source) {
        RetainedInputLease.Reference reference = admission.canonicalInput().retain(
                new ResourceOwner(ResourceOwner.Scope.QUEUE_ENTRY,
                        source.executionId() + "/attempt-" + source.attempt() + "/queue"));
        return new QueuedInputLease(reference::close);
    }

    ExecutionRecord.PhysicalInput open(InvocationTask source) {
        String attemptIdentity = source.executionId() + "/attempt-" + source.attempt();
        RetainedInputLease.Reference shared = admission.canonicalInput().retain(
                new ResourceOwner(ResourceOwner.Scope.PHYSICAL_ATTEMPT, attemptIdentity));
        ResourceQuota.Reservation copy = null;
        try {
            InvocationTask physicalTask = source;
            if (canonical.requiresMaterialization()) {
                copy = capacity.reserveInputCopy(
                        admission.canonicalInput().generation(),
                        new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, attemptIdentity + "/materialized"),
                        canonical.retainedBytes());
                physicalTask = new InvocationTask(
                        source.executionId(), source.functionName(), source.functionSpec(),
                        canonical.materializeRequest(), source.idempotencyKey(), source.traceId(),
                        source.enqueuedAt(), source.attempt(), source.kind(), source.dispatchLease());
            }
            return new ExecutionRecord.PhysicalInput(physicalTask, shared, copy);
        } catch (RuntimeException | Error failure) {
            if (copy != null) copy.close();
            shared.close();
            throw failure;
        }
    }
}
