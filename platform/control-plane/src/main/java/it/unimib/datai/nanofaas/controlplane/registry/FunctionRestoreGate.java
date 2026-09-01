package it.unimib.datai.nanofaas.controlplane.registry;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Gates registry mutations until the startup {@link FunctionCatalogRestorer} has rebuilt the
 * in-memory registry from the persisted catalog. While {@link #isReady()} is {@code false},
 * {@link FunctionService} rejects register/update/remove/setReplicas so a concurrent request
 * cannot be dropped by the restorer's final snapshot replacement.
 */
@Component
public final class FunctionRestoreGate {
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public boolean isReady() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
    }

    /** A gate that is already open, for contexts without an {@code ApplicationRunner} and for tests. */
    public static FunctionRestoreGate open() {
        FunctionRestoreGate gate = new FunctionRestoreGate();
        gate.markReady();
        return gate;
    }
}
