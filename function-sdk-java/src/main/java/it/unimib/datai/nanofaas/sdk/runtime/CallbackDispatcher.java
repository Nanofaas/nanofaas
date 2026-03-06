package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CallbackDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CallbackDispatcher.class);
    private static final int WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int QUEUE_CAPACITY = 128;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final CallbackClient callbackClient;
    private final ThreadPoolExecutor executor;

    public CallbackDispatcher(CallbackClient callbackClient) {
        this(callbackClient, new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("callback-dispatcher-" + THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()));
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor) {
        this.callbackClient = callbackClient;
        this.executor = executor;
    }

    public boolean submit(String executionId, InvocationResult result, String traceId) {
        try {
            executor.execute(() -> callbackClient.sendResult(executionId, result, traceId));
            return true;
        } catch (RejectedExecutionException ex) {
            log.warn("Dropping callback for execution {} because dispatcher queue is full", executionId);
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
