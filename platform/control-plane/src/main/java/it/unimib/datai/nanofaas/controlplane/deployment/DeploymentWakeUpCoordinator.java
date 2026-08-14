package it.unimib.datai.nanofaas.controlplane.deployment;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Serializes deployment wake-ups with scale-downs for each function. */
@Service
public class DeploymentWakeUpCoordinator {
    private final ConcurrentMap<String, FunctionState> functions = new ConcurrentHashMap<>();
    private final AtomicLong leaseIds = new AtomicLong();

    /**
     * Installs a monotonic wake-up lease and scales to one under the same per-function lock.
     */
    public void protectAndScaleUp(ManagedDeploymentTarget target, long deadlineNanos, Runnable scaleUp) {
        FunctionState state = functions.computeIfAbsent(target.functionName(), ignored -> new FunctionState());
        synchronized (state) {
            long leaseId = leaseIds.incrementAndGet();
            state.leaseId = leaseId;
            state.deadlineNanos = deadlineNanos;
            scheduleExpiry(target.functionName(), leaseId, deadlineNanos);
            scaleUp.run();
        }
    }

    /**
     * Runs a downscale only when no wake-up lease is active, atomically with that check.
     */
    public boolean scaleDownIfUnprotected(ManagedDeploymentTarget target, Runnable scaleDown) {
        FunctionState state = functions.computeIfAbsent(target.functionName(), ignored -> new FunctionState());
        synchronized (state) {
            expireIfDue(state, System.nanoTime());
            if (state.leaseId != 0) {
                return false;
            }
            scaleDown.run();
            return true;
        }
    }

    boolean isScaleDownProtected(String functionName) {
        FunctionState state = functions.get(functionName);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            expireIfDue(state, System.nanoTime());
            return state.leaseId != 0;
        }
    }

    public void removeFunctionState(String functionName) {
        functions.remove(functionName);
    }

    private void scheduleExpiry(String functionName, long leaseId, long deadlineNanos) {
        long delay = Math.max(0, deadlineNanos - System.nanoTime());
        CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS)
                .execute(() -> expire(functionName, leaseId, deadlineNanos));
    }

    private void expire(String functionName, long leaseId, long deadlineNanos) {
        FunctionState state = functions.get(functionName);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.leaseId != leaseId || state.deadlineNanos != deadlineNanos) {
                return;
            }
            long now = System.nanoTime();
            if (now < deadlineNanos) {
                scheduleExpiry(functionName, leaseId, deadlineNanos);
                return;
            }
            state.leaseId = 0;
            state.deadlineNanos = 0;
        }
    }

    private static void expireIfDue(FunctionState state, long now) {
        if (state.leaseId != 0 && now >= state.deadlineNanos) {
            state.leaseId = 0;
            state.deadlineNanos = 0;
        }
    }

    private static final class FunctionState {
        private long leaseId;
        private long deadlineNanos;
    }
}
