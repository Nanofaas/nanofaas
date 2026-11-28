package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

/**
 * A base for a test's own {@link ExecutionRecord} double, which overrides a method to count or
 * mutate: a record without input resources, as {@link ExecutionFixtures#record} builds.
 */
public class OverridableExecutionRecord extends ExecutionRecord {
    public OverridableExecutionRecord(String executionId, InvocationTask task) {
        super(executionId, task, TimeSource.system(), null);
    }
}
