package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queues callback delivery off the request thread.
 *
 * <p>The controller invokes this component after the handler returns so callback I/O does not block
 * the main request path longer than necessary. It depends on the {@link CallbackClient}, a bounded
 * worker pool, and daemon threads so the JVM can still shut down cleanly. When the queue is full,
 * callbacks are dropped rather than stalling the function invocation.</p>
 *
 * <p>Lifecycle boundary: the executor is process-scoped and is drained at shutdown. In-flight
 * callbacks may complete during shutdown, but the dispatcher stops accepting new work once Spring
 * begins teardown.</p>
 */
@Component
public class CallbackDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CallbackDispatcher.class);
    private static final int QUEUE_CAPACITY = 128;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final CallbackClient callbackClient;
    private final ThreadPoolExecutor executor;

    @Autowired
    public CallbackDispatcher(
            CallbackClient callbackClient,
            @Value("${nanofaas.callback.worker-count:2}") int workerCount) {
        this(callbackClient, new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("callback-dispatcher-" + THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);  // daemon: JVM can exit even if callbacks are in-flight
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
