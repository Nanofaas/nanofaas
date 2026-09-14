package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Executes the active handler within the runtime timeout boundary.
 *
 * <p>The executor isolates handler work from the request thread, enforces the configured timeout,
 * and uses virtual threads so blocking handler code does not pin a carrier thread while the invoke
 * lifecycle is still open.</p>
 */
@Component
public class HandlerExecutor {

    private final long timeoutMs;
    private final int maxConcurrent;
    private final ExecutorService executor;
    private final Semaphore admission;
    private final java.util.concurrent.atomic.AtomicBoolean accepting =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    @Autowired
    public HandlerExecutor(
            @Value("${nanofaas.handler.timeout-ms:${NANOFAAS_HANDLER_TIMEOUT:30000}}") long timeoutMs,
            @Value("${nanofaas.handler.max-concurrent:${NANOFAAS_MAX_CONCURRENT_HANDLERS:32}}") int maxConcurrent) {
        if (timeoutMs <= 0 || maxConcurrent <= 0) {
            throw new IllegalArgumentException("handler timeout and capacity must be positive");
        }
        this.timeoutMs = timeoutMs;
        this.maxConcurrent = maxConcurrent;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.admission = new Semaphore(maxConcurrent);
    }

    public HandlerExecutor(long timeoutMs) {
        this(timeoutMs, 32);
    }

    /** Select the admission outcome before reserving callback capacity; execute still acquires atomically. */
    void checkAvailability() {
        if (!accepting.get()) throw new RuntimeStoppingException();
        if (admission.availablePermits() == 0) throw new HandlerSaturatedException();
    }

    int activeHandlerCount() {
        return maxConcurrent - admission.availablePermits();
    }

    public Object execute(FunctionHandler handler, InvocationRequest request)
            throws InterruptedException, TimeoutException {
        if (!accepting.get()) throw new RuntimeStoppingException();
        if (!admission.tryAcquire()) {
            throw new HandlerSaturatedException();
        }
        if (!accepting.get()) {
            admission.release();
            throw new RuntimeStoppingException();
        }
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        final Future<Object> future;
        try {
            future = executor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    return handler.handle(request);
                } finally {
                    MDC.clear();
                    admission.release();
                }
            });
        } catch (RuntimeException ex) {
            admission.release();
            if (!accepting.get() && ex instanceof RejectedExecutionException) {
                throw new RuntimeStoppingException();
            }
            throw ex;
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) {
                throw sneakyThrow(e);
            }
            throw new RuntimeException(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @PreDestroy
    void shutdown() {
        accepting.set(false);
        executor.shutdownNow();
    }
}
