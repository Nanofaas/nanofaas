package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/**
 * A base for a test's own {@link ExecutionRecord} double, which overrides a method to count or
 * mutate, admitted as {@link ExecutionFixtures#record} admits one.
 */
public class OverridableExecutionRecord extends ExecutionRecord {
    public OverridableExecutionRecord(String executionId, InvocationTask task) {
        this(executionId, task, new FunctionCapacityRegistry());
    }

    public OverridableExecutionRecord(String executionId, InvocationTask task, FunctionCapacityRegistry generations) {
        super(executionId, task, TimeSource.system(), ExecutionFixtures.admit(executionId, task, generations));
    }
}
