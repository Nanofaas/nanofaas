package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Handles the control-plane invoke request for a single function execution.
 *
 * <p>The control plane calls {@code /invoke}; this controller resolves the effective execution and
 * trace context, rejects requests that arrive without an execution identifier, tracks cold-start
 * state, dispatches the active handler, and posts the result back to the control plane as a
 * callback.</p>
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
    private final JsonOutputNormalizer outputNormalizer;

    public InvokeController(
            CallbackDispatcher callbackDispatcher,
            HandlerRegistry handlerRegistry,
            InvocationRuntimeContextResolver runtimeContextResolver,
            ColdStartTracker coldStartTracker,
            HandlerExecutor handlerExecutor,
            JsonOutputNormalizer outputNormalizer) {
        this.callbackDispatcher = callbackDispatcher;
        this.handlerRegistry = handlerRegistry;
        this.runtimeContextResolver = runtimeContextResolver;
        this.coldStartTracker = coldStartTracker;
        this.handlerExecutor = handlerExecutor;
        this.outputNormalizer = outputNormalizer;
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
            Object rawOutput = handlerExecutor.execute(handler, request);
            JsonNode output = outputNormalizer.toJsonNode(rawOutput);

            callbackDispatcher.submit(
                    effectiveExecutionId,
                    CallbackPayload.success(output),
                    runtimeContext.traceId());

            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
            if (isColdStart) {
                responseBuilder.header("X-Cold-Start", "true");
                responseBuilder.header("X-Init-Duration-Ms", String.valueOf(coldStartTracker.initDurationMs()));
            }
            return responseBuilder.body(output);
        } catch (OutputSerializationException ex) {
            String errorMessage = ex.getMessage();
            log.error("Handler output serialization failed for execution {}: {}", effectiveExecutionId, errorMessage, ex);
            callbackDispatcher.submit(
                    effectiveExecutionId,
                    CallbackPayload.error("OUTPUT_SERIALIZATION_ERROR", errorMessage),
                    runtimeContext.traceId());
            return ResponseEntity.status(500)
                    .body(Map.of("error", errorMessage));
        } catch (TimeoutException ex) {
            log.error("Handler timed out for execution {}", effectiveExecutionId);
            callbackDispatcher.submit(
                    effectiveExecutionId,
                    CallbackPayload.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
                    runtimeContext.traceId());
            return ResponseEntity.status(504).body(Map.of("error", "Handler timed out"));
        } catch (Exception ex) {
            String errorMessage = handlerErrorMessage(ex);
            log.error("Handler error for execution {}: {}", effectiveExecutionId, errorMessage, ex);

            callbackDispatcher.submit(
                    effectiveExecutionId,
                    CallbackPayload.error("HANDLER_ERROR", errorMessage),
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
