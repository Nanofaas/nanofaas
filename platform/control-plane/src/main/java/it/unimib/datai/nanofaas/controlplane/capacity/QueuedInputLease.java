package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** The canonical-input reference carried by a task while a queue physically retains it. */
public final class QueuedInputLease implements AutoCloseable {
    private final RetainedInputLease.Reference reference;
    private final AtomicBoolean closed = new AtomicBoolean();

    public QueuedInputLease(RetainedInputLease.Reference reference) {
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) reference.close();
    }
}
