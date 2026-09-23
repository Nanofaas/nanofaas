package it.unimib.datai.nanofaas.sdk.runtime;

import static it.unimib.datai.nanofaas.common.logging.LogSanitizer.singleLine;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

/**
 * Asynchronously hands callback delivery off to a bounded worker pool.
 *
 * <p>The dispatcher exists so the invoke path can return while callback delivery is still being
 * attempted, but it keeps the queue and worker count bounded so callback pressure cannot grow
 * without limit inside the function container.</p>
 */
@Component
public class CallbackDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CallbackDispatcher.class);
    private static final int QUEUE_CAPACITY = 128;
    private static final long DEFAULT_MAX_PENDING_BYTES = 16L * 1024 * 1024;
    private static final int DEFAULT_MAX_CALLBACK_BYTES = 2 * 1024 * 1024;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final CallbackClient callbackClient;
    private final ThreadPoolExecutor executor;
    private final RuntimeMetricsFilter runtimeMetrics;
    private final int maxPendingCallbacks;
    private final long maxPendingCallbackBytes;
    private final int maxCallbackBytes;
    private final Duration shutdownTimeout;
    private final Object capacityLock = new Object();
    private int pendingCallbackCount;
    private long pendingCallbackBytes;

    @Autowired
    public CallbackDispatcher(
            CallbackClient callbackClient,
            RuntimeMetricsFilter runtimeMetrics,
            @Value("${nanofaas.callback.worker-count:2}") int workerCount,
            @Value("${nanofaas.callback.max-pending:128}") int maxPendingCallbacks,
            @Value("${nanofaas.callback.max-pending-bytes:16777216}") long maxPendingCallbackBytes,
            @Value("${nanofaas.callback.max-payload-bytes:2097152}") int maxCallbackBytes) {
        this(callbackClient, newExecutor(workerCount), runtimeMetrics,
                maxPendingCallbacks, maxPendingCallbackBytes, maxCallbackBytes,
                Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    public CallbackDispatcher(CallbackClient callbackClient, int workerCount) {
        this(callbackClient, newExecutor(workerCount), null,
                QUEUE_CAPACITY + workerCount, DEFAULT_MAX_PENDING_BYTES, DEFAULT_MAX_CALLBACK_BYTES,
                Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    private static ThreadPoolExecutor newExecutor(int workerCount) {
        return new ThreadPoolExecutor(
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
                new ThreadPoolExecutor.AbortPolicy());
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor) {
        this(callbackClient, executor, null, executor.getMaximumPoolSize() + executor.getQueue().remainingCapacity(),
                DEFAULT_MAX_PENDING_BYTES, DEFAULT_MAX_CALLBACK_BYTES,
                Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor, RuntimeMetricsFilter runtimeMetrics) {
        this(callbackClient, executor, runtimeMetrics,
                executor.getMaximumPoolSize() + executor.getQueue().remainingCapacity(),
                DEFAULT_MAX_PENDING_BYTES, DEFAULT_MAX_CALLBACK_BYTES,
                Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor,
                       RuntimeMetricsFilter runtimeMetrics, int maxPendingCallbacks,
                       long maxPendingCallbackBytes) {
        this(callbackClient, executor, runtimeMetrics, maxPendingCallbacks,
                maxPendingCallbackBytes, Math.toIntExact(Math.min(Integer.MAX_VALUE, maxPendingCallbackBytes)),
                Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor,
                       RuntimeMetricsFilter runtimeMetrics, int maxPendingCallbacks,
                       long maxPendingCallbackBytes, int maxCallbackBytes) {
        this(callbackClient, executor, runtimeMetrics, maxPendingCallbacks,
                maxPendingCallbackBytes, maxCallbackBytes,
                Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS));
    }

    CallbackDispatcher(CallbackClient callbackClient, ThreadPoolExecutor executor,
                       RuntimeMetricsFilter runtimeMetrics, int maxPendingCallbacks,
                       long maxPendingCallbackBytes, int maxCallbackBytes, Duration shutdownTimeout) {
        if (maxPendingCallbacks <= 0 || maxPendingCallbackBytes <= 0 || maxCallbackBytes <= 0
                || maxCallbackBytes > maxPendingCallbackBytes) {
            throw new IllegalArgumentException("callback limits must be positive and the single payload must fit the aggregate");
        }
        this.callbackClient = callbackClient;
        this.executor = executor;
        this.runtimeMetrics = runtimeMetrics;
        this.maxPendingCallbacks = maxPendingCallbacks;
        this.maxPendingCallbackBytes = maxPendingCallbackBytes;
        this.maxCallbackBytes = maxCallbackBytes;
        this.shutdownTimeout = shutdownTimeout;
    }

    public CallbackReservation tryReserve(long retainedBytes) {
        if (retainedBytes < 0 || retainedBytes > maxCallbackBytes) {
            return null;
        }
        synchronized (capacityLock) {
            if (executor.isShutdown() || pendingCallbackCount >= maxPendingCallbacks
                    || retainedBytes > maxPendingCallbackBytes - pendingCallbackBytes) {
                return null;
            }
            pendingCallbackCount++;
            pendingCallbackBytes += retainedBytes;
            return new CallbackReservation(retainedBytes);
        }
    }

    public CallbackReservation reserveInvocation() {
        CallbackReservation reservation = tryReserve(maxCallbackBytes);
        if (reservation == null) throw new CallbackSaturatedException();
        return reservation;
    }

    int pendingCallbackCount() {
        synchronized (capacityLock) {
            return pendingCallbackCount;
        }
    }

    long pendingCallbackBytes() {
        synchronized (capacityLock) {
            return pendingCallbackBytes;
        }
    }

    public final class CallbackReservation implements AutoCloseable {
        private final long retainedBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CallbackReservation(long retainedBytes) {
            this.retainedBytes = retainedBytes;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (capacityLock) {
                pendingCallbackCount--;
                pendingCallbackBytes -= retainedBytes;
            }
        }
    }

    public boolean submit(String executionId, CallbackPayload payload, String traceId) {
        return submit(executionId, payload, traceId, null);
    }

    public boolean submit(String executionId, CallbackPayload payload, String traceId, String dispatchAttempt) {
        CallbackReservation reservation = tryReserve(maxCallbackBytes);
        if (reservation == null) {
            recordRejection(executionId);
            return false;
        }
        return submit(reservation, executionId, payload, traceId, dispatchAttempt) == SubmitResult.ACCEPTED;
    }

    public SubmitResult submit(CallbackReservation reservation, String executionId, CallbackPayload payload,
                               String traceId, String dispatchAttempt) {
        if (reservation == null) {
            return SubmitResult.SATURATED;
        }
        try {
            byte[] serialized = callbackClient.serializeBounded(payload, maxCallbackBytes);
            CallbackTask task = new CallbackTask(
                    reservation, executionId, serialized, traceId, dispatchAttempt);
            try {
                executor.execute(task);
                return SubmitResult.ACCEPTED;
            } catch (RejectedExecutionException _) {
                task.release();
                recordRejection(executionId);
                return SubmitResult.SATURATED;
            }
        } catch (BoundedJson.PayloadTooLargeException _) {
            reservation.close();
            recordRejection(executionId);
            return SubmitResult.PAYLOAD_TOO_LARGE;
        } catch (BoundedJson.SerializationException ex) {
            reservation.close();
            recordRejection(executionId);
            log.warn("Rejecting unserializable callback for execution {}", singleLine(executionId), ex);
            return SubmitResult.SERIALIZATION_FAILED;
        }
    }

    private void recordRejection(String executionId) {
        log.warn("Rejecting callback for execution {} because dispatcher capacity is full",
                singleLine(executionId));
        if (runtimeMetrics != null) {
            runtimeMetrics.recordCallbackFailure();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                releaseCancelled(executor.shutdownNow());
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            releaseCancelled(executor.shutdownNow());
        }
    }

    private static void releaseCancelled(List<Runnable> cancelled) {
        cancelled.forEach(runnable -> {
            if (runnable instanceof CallbackTask task) {
                task.release();
            }
        });
    }

    public enum SubmitResult { ACCEPTED, SATURATED, PAYLOAD_TOO_LARGE, SERIALIZATION_FAILED }

    private final class CallbackTask implements Runnable {
        private final CallbackReservation reservation;
        private final String executionId;
        private final byte[] payload;
        private final String traceId;
        private final String dispatchAttempt;

        private CallbackTask(CallbackReservation reservation, String executionId, byte[] payload,
                             String traceId, String dispatchAttempt) {
            this.reservation = reservation;
            this.executionId = executionId;
            this.payload = payload;
            this.traceId = traceId;
            this.dispatchAttempt = dispatchAttempt;
        }

        @Override
        public void run() {
            try {
                if (!callbackClient.sendSerializedResult(executionId, payload, traceId, dispatchAttempt)
                        && runtimeMetrics != null) {
                    runtimeMetrics.recordCallbackFailure();
                }
            } finally {
                release();
            }
        }

        private void release() {
            reservation.close();
        }
    }
}
