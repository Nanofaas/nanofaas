package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One attempt's hold on the execution's retained input, for as long as its transport may read it.
 * Closing it, exactly once, returns that hold; the input itself is released when its last holder
 * closes (see {@link ExecutionInputResources}).
 */
public final class PhysicalInput implements AutoCloseable {
    private final InvocationTask task;
    private final AutoCloseable sharedReference;
    private final AtomicBoolean closed = new AtomicBoolean();

    PhysicalInput(InvocationTask task, AutoCloseable sharedReference) {
        this.task = task;
        this.sharedReference = sharedReference;
    }

    public InvocationTask task() {
        return task;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeUnchecked(sharedReference);
    }

    private static void closeUnchecked(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            throw failure;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
