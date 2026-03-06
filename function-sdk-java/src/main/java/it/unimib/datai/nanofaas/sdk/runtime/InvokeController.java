package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class InvokeController {
    private static final Logger log = LoggerFactory.getLogger(InvokeController.class);
    private static final String DEFAULT_HANDLER_ERROR_MESSAGE = "Handler execution failed";

    private final CallbackDispatcher callbackDispatcher;
    private final HandlerRegistry handlerRegistry;
    private final InvocationRuntimeContextResolver runtimeContextResolver;
    private final ColdStartTracker coldStartTracker;

    public InvokeController(
            CallbackDispatcher callbackDispatcher,
            HandlerRegistry handlerRegistry,
            InvocationRuntimeContextResolver runtimeContextResolver,
            ColdStartTracker coldStartTracker) {
        this.callbackDispatcher = callbackDispatcher;
        this.handlerRegistry = handlerRegistry;
        this.runtimeContextResolver = runtimeContextResolver;
        this.coldStartTracker = coldStartTracker;
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

        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object output = handler.handle(request);

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
