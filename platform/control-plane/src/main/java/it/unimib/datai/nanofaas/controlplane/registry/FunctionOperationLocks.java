package it.unimib.datai.nanofaas.controlplane.registry;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Per-function mutual exclusion shared by every operation that mutates a single function's state,
 * so the {@code FunctionService} lifecycle and the managed-deployment coordinator serialize against
 * each other on the same lock.
 */
@Component
public final class FunctionOperationLocks {
    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String functionName, Supplier<T> action) {
        LockEntry entry = acquire(functionName);
        entry.lock.lock();
        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            release(functionName, entry);
        }
    }

    public void withLock(String functionName, Runnable action) {
        withLock(functionName, () -> {
            action.run();
            return null;
        });
    }

    private LockEntry acquire(String functionName) {
        return locks.compute(functionName, (ignored, existing) -> {
            LockEntry entry = existing == null ? new LockEntry() : existing;
            entry.users++;
            return entry;
        });
    }

    private void release(String functionName, LockEntry lockEntry) {
        locks.computeIfPresent(functionName, (ignored, existing) -> {
            if (existing != lockEntry) {
                return existing;
            }
            existing.users--;
            return existing.users == 0 ? null : existing;
        });
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
