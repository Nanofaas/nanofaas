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
    private static final int WORKER_COUNT = 1;
    private static final int QUEUE_CAPACITY = 128;

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
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()));
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor) {
        this.callbackClient = callbackClient;
        this.executor = executor;
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

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
    }
}
