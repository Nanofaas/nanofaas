package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.QueuedInputLease;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceOwner;
import it.unimib.datai.nanofaas.controlplane.capacity.RetainedInputLease;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/** Capacity ownership attached to one logical execution without duplicating generation identity. */
final class ExecutionInputResources {
    private final InvocationCapacity.Admission admission;

    ExecutionInputResources(InvocationCapacity.Admission admission) {
        this.admission = admission;
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

    /**
     * One more owner of the retained input, for as long as an attempt's transport may read it.
     * The input is immutable text (or a scalar), so the attempt reads the retained value itself:
     * there is no per-attempt copy to make or to account for.
     */
    ExecutionRecord.PhysicalInput open(InvocationTask source) {
        RetainedInputLease.Reference shared = admission.canonicalInput().retain(
                new ResourceOwner(ResourceOwner.Scope.PHYSICAL_ATTEMPT,
                        source.executionId() + "/attempt-" + source.attempt()));
        return new ExecutionRecord.PhysicalInput(source, shared);
    }
}
