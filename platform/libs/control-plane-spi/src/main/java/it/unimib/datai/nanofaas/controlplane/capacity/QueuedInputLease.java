package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The canonical-input reference carried by a task while a queue physically retains it.
 *
 * <p>The lease holds a release action rather than the core retained-input reference itself: the
 * task that carries it is an SPI contract, so its component types cannot name a core
 * implementation. Core constructs it with a method reference to the real reference's
 * {@code close}, so the released resource and the release-once guarantee are unchanged.</p>
 */
public final class QueuedInputLease implements AutoCloseable {
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    public QueuedInputLease(Runnable release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }
}
