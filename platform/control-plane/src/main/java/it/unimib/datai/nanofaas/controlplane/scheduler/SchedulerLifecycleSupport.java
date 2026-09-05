package it.unimib.datai.nanofaas.controlplane.scheduler;

import org.slf4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SchedulerLifecycleSupport {
    private SchedulerLifecycleSupport() {
    }

    public static ExecutorService newSingleThreadExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(false);
            return t;
        });
    }

    /**
     * A fixed-resource pool for work that must never grow unbounded: {@code corePoolSize}
     * threads, growing to {@code maxPoolSize} only once the {@code queueCapacity}-deep
     * work queue backs up, and {@link ThreadPoolExecutor.AbortPolicy} beyond that so a
     * saturated pool fails the submission (a {@link java.util.concurrent.RejectedExecutionException})
     * instead of silently running the task on the caller's own thread - the caller here is
     * frequently a previous task's own completion callback, and running inline would let a
     * long chain of immediately-resolving retries recurse the call stack instead of handing
     * each attempt to a fresh loop iteration on a worker thread.
     */
    public static ExecutorService newBoundedExecutor(String threadNamePrefix,
                                                      int corePoolSize,
                                                      int maxPoolSize,
                                                      int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, threadNamePrefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        RejectedExecutionHandler rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy();
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                factory,
                rejectedExecutionHandler);
    }

    public static void shutdownExecutor(ExecutorService executor, Logger log, String componentName) {
        if (executor == null) {
            return;
        }
        log.info("{} stopping...", componentName);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate in time, forcing shutdown", componentName);
                executor.shutdownNow();
            }
        } catch (InterruptedException _) {
            log.warn("{} shutdown interrupted", componentName);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("{} stopped", componentName);
    }
}
