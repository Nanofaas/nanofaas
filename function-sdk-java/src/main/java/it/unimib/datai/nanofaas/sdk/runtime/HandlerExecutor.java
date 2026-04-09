package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/**
 * Runs the user handler under a bounded timeout.
 *
 * <p>This component exists to isolate handler latency from the rest of the runtime. The controller
 * depends on it to enforce the invocation timeout boundary, and the runtime uses virtual threads so
 * blocking user code does not pin the carrier thread pool.</p>
 *
 * <p>Lifecycle boundary: execution ends when the handler returns, throws, or exceeds the configured
 * timeout. Any cleanup after that belongs to the controller or callback dispatcher.</p>
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
