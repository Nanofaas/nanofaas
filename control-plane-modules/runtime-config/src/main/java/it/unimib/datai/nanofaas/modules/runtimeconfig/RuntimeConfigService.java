package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current runtime configuration snapshot and handles
 * compare-and-set updates with revision-based optimistic locking.
 */
@Service
public class RuntimeConfigService {

    private final AtomicReference<RuntimeConfigSnapshot> current;

    public RuntimeConfigService(RateLimiter rateLimiter, SyncQueueRuntimeDefaults syncQueueDefaults) {
        RuntimeConfigSnapshot initial = new RuntimeConfigSnapshot(
                0L,
                rateLimiter.getMaxPerSecond(),
                syncQueueDefaults.enabled(),
                syncQueueDefaults.admissionEnabled(),
                syncQueueDefaults.maxEstimatedWait(),
                syncQueueDefaults.maxQueueWait(),
                syncQueueDefaults.retryAfterSeconds()
        );
        this.current = new AtomicReference<>(initial);
    }

    public RuntimeConfigSnapshot getSnapshot() {
        return current.get();
    }

    /**
     * Atomically applies a patch if the current revision matches {@code expectedRevision}.
     *
     * @return the new snapshot after successful update
     * @throws RevisionMismatchException if current revision != expectedRevision
     */
    public RuntimeConfigSnapshot update(long expectedRevision, RuntimeConfigPatch patch) {
        while (true) {
            RuntimeConfigSnapshot snapshot = current.get();
            if (snapshot.revision() != expectedRevision) {
                throw new RevisionMismatchException(expectedRevision, snapshot.revision());
            }
            RuntimeConfigSnapshot candidate = snapshot.applyPatch(patch);
            if (current.compareAndSet(snapshot, candidate)) {
                return candidate;
            }
            // CAS failed because another thread updated; retry loop will re-check revision
        }
    }

    /**
     * Restores a previous snapshot (used for rollback on apply failure).
     */
    void restore(RuntimeConfigSnapshot previous) {
        current.set(previous);
    }
}
