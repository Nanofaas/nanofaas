package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Cache timers a test fires by hand: the stand-in for the wall time a fake ticker skips. Caffeine
 * schedules its maintenance here instead of on a real clock, and {@link #fire()} runs what is
 * pending, on the cache's own executor, as the real timer would once its deadline passed.
 */
public final class ManualScheduler implements Scheduler {
    private record Timer(Executor executor, Runnable command, CompletableFuture<Void> handle) {
    }

    private final List<Timer> pending = new ArrayList<>();

    @Override
    public synchronized Future<?> schedule(Executor executor, Runnable command, long delay, TimeUnit unit) {
        Timer timer = new Timer(executor, command, new CompletableFuture<>());
        pending.add(timer);
        return timer.handle();
    }

    /** Runs every timer still pending, as if its deadline had passed. */
    public void fire() {
        List<Timer> due;
        synchronized (this) {
            due = new ArrayList<>(pending);
            pending.clear();
        }
        for (Timer timer : due) {
            if (timer.handle().complete(null)) {
                timer.executor().execute(timer.command());
            }
        }
    }
}
