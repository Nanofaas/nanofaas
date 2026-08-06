package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
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
    private final ExecutorService executor;

    public HandlerExecutor(
            @Value("${nanofaas.handler.timeout-ms:${NANOFAAS_HANDLER_TIMEOUT:30000}}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public Object execute(FunctionHandler handler, InvocationRequest request)
            throws InterruptedException, TimeoutException {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Future<Object> future = executor.submit(() -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                return handler.handle(request);
            } finally {
                MDC.clear();
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
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
        executor.shutdownNow();
    }
}
