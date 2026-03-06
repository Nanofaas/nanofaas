package it.unimib.datai.nanofaas.controlplane.scheduler;

import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        } catch (InterruptedException ex) {
            log.warn("{} shutdown interrupted", componentName);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("{} stopped", componentName);
    }
}
