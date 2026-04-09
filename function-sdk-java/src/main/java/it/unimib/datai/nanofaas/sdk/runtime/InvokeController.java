package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Entry point for control-plane invocations.
 *
 * <p>The control plane calls this controller on every function execution. It resolves the effective
 * execution context from request headers and startup env vars, enforces the handler timeout, tracks
 * cold-start state, and sends the callback result back to the control plane.</p>
 *
 * <p>Environment assumptions: this controller runs inside Spring MVC with the auto-configured
 * runtime beans already present. It assumes one active handler bean exists and that callback
 * delivery is configured through {@code CALLBACK_URL} when results must be reported back.</p>
 *
 * <p>Historical note: the current code path replaced an earlier, more ad hoc invocation pipeline
 * with a single controller-based execution flow so the runtime contract is easier to trace from the
 * source code.</p>
 */
@RestController
public class InvokeController {
    private static final Logger log = LoggerFactory.getLogger(InvokeController.class);
    private static final String DEFAULT_HANDLER_ERROR_MESSAGE = "Handler execution failed";

    private final CallbackDispatcher callbackDispatcher;
    private final HandlerRegistry handlerRegistry;
    private final InvocationRuntimeContextResolver runtimeContextResolver;
    private final ColdStartTracker coldStartTracker;
    private final HandlerExecutor handlerExecutor;

    public InvokeController(
            CallbackDispatcher callbackDispatcher,
            HandlerRegistry handlerRegistry,
            InvocationRuntimeContextResolver runtimeContextResolver,
            ColdStartTracker coldStartTracker,
            HandlerExecutor handlerExecutor) {
        this.callbackDispatcher = callbackDispatcher;
        this.handlerRegistry = handlerRegistry;
        this.runtimeContextResolver = runtimeContextResolver;
        this.coldStartTracker = coldStartTracker;
        this.handlerExecutor = handlerExecutor;
    }

    @PostMapping("/invoke")
    public ResponseEntity<Object> invoke(
            @RequestBody InvocationRequest request,
            @RequestHeader(value = "X-Execution-Id", required = false) String headerExecutionId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        InvocationRuntimeContext runtimeContext = runtimeContextResolver.resolve(headerExecutionId, traceId);
        String effectiveExecutionId = runtimeContext.executionId();

        if (effectiveExecutionId == null || effectiveExecutionId.isBlank()) {
            log.error("No execution ID provided (header or ENV)");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Execution ID not configured"));
        }

        boolean isColdStart = coldStartTracker.firstInvocation();
        coldStartTracker.markFirstRequestArrival(); // idempotente: solo la prima chiamata ha effetto

        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object output = handlerExecutor.execute(handler, request);

            callbackDispatcher.submit(
                    effectiveExecutionId,
                    InvocationResult.success(output),
                    runtimeContext.traceId());

            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
            if (isColdStart) {
                responseBuilder.header("X-Cold-Start", "true");
                responseBuilder.header("X-Init-Duration-Ms", String.valueOf(coldStartTracker.initDurationMs()));
            }
            return responseBuilder.body(output);
        } catch (TimeoutException ex) {
            log.error("Handler timed out for execution {}", effectiveExecutionId);
            callbackDispatcher.submit(
                    effectiveExecutionId,
                    InvocationResult.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
                    runtimeContext.traceId());
            return ResponseEntity.status(504).body(Map.of("error", "Handler timed out"));
        } catch (Exception ex) {
            String errorMessage = handlerErrorMessage(ex);
            log.error("Handler error for execution {}: {}", effectiveExecutionId, errorMessage, ex);

            callbackDispatcher.submit(
                    effectiveExecutionId,
                    InvocationResult.error("HANDLER_ERROR", errorMessage),
                    runtimeContext.traceId());

            return ResponseEntity.status(500)
                    .body(Map.of("error", errorMessage));
        }
    }

    private static String handlerErrorMessage(Exception ex) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? DEFAULT_HANDLER_ERROR_MESSAGE : message;
    }
}
