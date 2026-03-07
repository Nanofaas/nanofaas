package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Executes a FunctionHandler with a configurable timeout.
 * Uses virtual threads (Java 21) to avoid blocking carrier threads.
 */
@Component
public class HandlerExecutor {

    private final long timeoutMs;
    private final ExecutorService executor;

    public HandlerExecutor(
            @Value("${nanofaas.handler.timeout-ms:30000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executes handler.handle(request) within the configured timeout.
     *
     * @throws TimeoutException     if the handler exceeds the timeout
     * @throws InterruptedException if the calling thread is interrupted
     * @throws Exception            any exception thrown by the handler
     */
    public Object execute(FunctionHandler handler, InvocationRequest request) throws Exception {
        Future<Object> future = executor.submit(() -> handler.handle(request));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(cause);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
