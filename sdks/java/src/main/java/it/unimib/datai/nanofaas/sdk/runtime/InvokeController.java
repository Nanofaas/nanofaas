package it.unimib.datai.nanofaas.sdk.runtime;

import static it.unimib.datai.nanofaas.common.logging.LogSanitizer.singleLine;

import tools.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private static final String ERROR_KEY = "error";

    private final CallbackDispatcher callbackDispatcher;
    private final HandlerRegistry handlerRegistry;
    private final InvocationRuntimeContextResolver runtimeContextResolver;
    private final ColdStartTracker coldStartTracker;
    private final HandlerExecutor handlerExecutor;
    private final RuntimePayloadLimits payloadLimits;

    @Autowired
    @SuppressWarnings("UnusedVariable") // Kept in the public constructor for source compatibility.
    public InvokeController(
            CallbackDispatcher callbackDispatcher,
            HandlerRegistry handlerRegistry,
            InvocationRuntimeContextResolver runtimeContextResolver,
            ColdStartTracker coldStartTracker,
            HandlerExecutor handlerExecutor,
            JsonOutputNormalizer outputNormalizer,
            RuntimePayloadLimits payloadLimits) {
        this.callbackDispatcher = callbackDispatcher;
        this.handlerRegistry = handlerRegistry;
        this.runtimeContextResolver = runtimeContextResolver;
        this.coldStartTracker = coldStartTracker;
        this.handlerExecutor = handlerExecutor;
        this.payloadLimits = payloadLimits;
    }

    @SuppressWarnings("UnusedVariable") // Kept in the public constructor for source compatibility.
    public InvokeController(CallbackDispatcher callbackDispatcher, HandlerRegistry handlerRegistry,
                            InvocationRuntimeContextResolver runtimeContextResolver,
                            ColdStartTracker coldStartTracker, HandlerExecutor handlerExecutor,
                            JsonOutputNormalizer outputNormalizer) {
        this(callbackDispatcher, handlerRegistry, runtimeContextResolver, coldStartTracker,
                handlerExecutor, outputNormalizer,
                new RuntimePayloadLimits(tools.jackson.databind.json.JsonMapper.builder().build(), 1024 * 1024));
    }

    @PostMapping("/invoke")
    public ResponseEntity<Object> invoke(
            @RequestBody InvocationRequest request,
            @RequestHeader(value = "X-Execution-Id", required = false) String headerExecutionId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Dispatch-Attempt", required = false) String dispatchAttempt) {

        InvocationRuntimeContext runtimeContext = runtimeContextResolver.resolve(headerExecutionId, traceId);
        String effectiveExecutionId = runtimeContext.executionId();

        if (effectiveExecutionId == null || effectiveExecutionId.isBlank()) {
            log.error("No execution ID provided (header or ENV)");
            return ResponseEntity.badRequest()
                    .body(Map.of(ERROR_KEY, "Execution ID not configured"));
        }

        CallbackDispatcher.CallbackReservation callbackReservation;
        try {
            handlerExecutor.checkAvailability();
            callbackReservation = callbackDispatcher.reserveInvocation();
        } catch (RuntimeStoppingException _) {
            return ResponseEntity.status(503).header("Retry-After", "1")
                    .body(errorBody("RUNTIME_STOPPING", "Runtime is stopping"));
        } catch (HandlerSaturatedException _) {
            return ResponseEntity.status(429).header("Retry-After", "1")
                    .body(errorBody("RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted"));
        } catch (CallbackSaturatedException _) {
            return ResponseEntity.status(429).header("Retry-After", "1").body(Map.of(
                    ERROR_KEY, Map.of("code", "RUNTIME_CALLBACK_SATURATED",
                            "message", "Runtime callback capacity exhausted")));
        }

        boolean isColdStart = coldStartTracker.firstInvocation();
        coldStartTracker.markFirstRequestArrival(); // idempotente: solo la prima chiamata ha effetto

        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object rawOutput = handlerExecutor.execute(handler, request);
            return buildSuccessResponse(rawOutput, effectiveExecutionId, runtimeContext,
                    dispatchAttempt, isColdStart, callbackReservation);
        } catch (OutputSerializationException ex) {
            String errorMessage = ex.getMessage();
            log.error("Handler output serialization failed for execution {}: {}",
                    singleLine(effectiveExecutionId), singleLine(errorMessage), ex);
            submitCallback(callbackReservation,
                    effectiveExecutionId,
                    CallbackPayload.error("OUTPUT_SERIALIZATION_ERROR", errorMessage),
                    runtimeContext.traceId(),
                    dispatchAttempt);
            return ResponseEntity.status(500)
                    .body(errorBody("OUTPUT_SERIALIZATION_ERROR", errorMessage));
        } catch (HandlerSaturatedException _) {
            if (callbackReservation != null) callbackReservation.close();
            return ResponseEntity.status(429)
                    .header("Retry-After", "1")
                    .body(Map.of(ERROR_KEY, Map.of(
                            "code", "RUNTIME_HANDLER_SATURATED",
                            "message", "Runtime handler capacity exhausted")));
        } catch (RuntimeStoppingException _) {
            if (callbackReservation != null) callbackReservation.close();
            return ResponseEntity.status(503)
                    .header("Retry-After", "1")
                    .body(Map.of(ERROR_KEY, Map.of(
                            "code", "RUNTIME_STOPPING", "message", "Runtime is stopping")));
        } catch (TimeoutException _) {
            log.error("Handler timed out for execution {}", singleLine(effectiveExecutionId));
            submitCallback(callbackReservation,
                    effectiveExecutionId,
                    CallbackPayload.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
                    runtimeContext.traceId(),
                    dispatchAttempt);
            return ResponseEntity.status(504).body(errorBody(
                    "HANDLER_TIMEOUT", "Handler exceeded configured timeout"));
        } catch (InterruptedException ex) {
            submitCallback(callbackReservation, effectiveExecutionId,
                    CallbackPayload.error("INVOCATION_CANCELLED", "Invocation cancelled"),
                    runtimeContext.traceId(), dispatchAttempt);
            Thread.currentThread().interrupt();
            throw new InvocationCancelledException(ex);
        } catch (Exception ex) {
            return handleHandlerFailure(ex, callbackReservation,
                    effectiveExecutionId, runtimeContext.traceId(), dispatchAttempt);
        }
    }

    private ResponseEntity<Object> buildSuccessResponse(Object rawOutput, String executionId,
                                                          InvocationRuntimeContext runtimeContext,
                                                          String dispatchAttempt, boolean isColdStart,
                                                          CallbackDispatcher.CallbackReservation callbackReservation) {
        int statusCode = 200;
        Map<String, String> allowedHeaders = Map.of();
        String encoding = null;
        Object outputForSerialization = rawOutput;
        boolean isEnvelope = false;

        if (rawOutput instanceof HandlerResponse(var envelopeOutput, var envelopeStatus,
                                                  var envelopeHeaders, var envelopeEncoding)) {
            if (ResponseHeaderPolicy.isStatusCodeValid(envelopeStatus)) {
                statusCode = envelopeStatus;
                allowedHeaders = ResponseHeaderPolicy.filterAllowedHeaders(envelopeHeaders);
                warnOnDroppedHeaders(envelopeHeaders, allowedHeaders, executionId);
                encoding = envelopeEncoding;
                outputForSerialization = envelopeOutput;
                isEnvelope = true;
            } else {
                log.warn("Handler returned invalid statusCode {} for execution {}, treating as platform error",
                        envelopeStatus, singleLine(executionId));
                submitCallback(callbackReservation, executionId,
                        CallbackPayload.error("OUTPUT_SERIALIZATION_ERROR",
                                "Handler returned invalid statusCode: " + envelopeStatus),
                        runtimeContext.traceId(), dispatchAttempt);
                return ResponseEntity.status(500)
                        .body(errorBody("OUTPUT_SERIALIZATION_ERROR",
                                "Handler returned invalid statusCode: " + envelopeStatus));
            }
        }

        final JsonNode output;
        try {
            output = payloadLimits.normalize(outputForSerialization);
        } catch (BoundedJson.PayloadTooLargeException _) {
            String message = "Runtime output exceeds configured byte limit";
            submitCallback(callbackReservation, executionId,
                    CallbackPayload.error("RUNTIME_OUTPUT_TOO_LARGE", message),
                    runtimeContext.traceId(), dispatchAttempt);
            return ResponseEntity.status(500).body(Map.of(
                    ERROR_KEY, Map.of("code", "RUNTIME_OUTPUT_TOO_LARGE", "message", message)));
        }

        CallbackDispatcher.SubmitResult handoff = submitCallback(callbackReservation,
                executionId,
                isEnvelope
                        ? CallbackPayload.successWithEnvelope(output, statusCode, allowedHeaders, encoding)
                        : CallbackPayload.success(output),
                runtimeContext.traceId(),
                dispatchAttempt);
        if (handoff != CallbackDispatcher.SubmitResult.ACCEPTED) {
            return callbackHandoffFailure(handoff);
        }

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(statusCode);
        allowedHeaders.forEach(responseBuilder::header);
        if (isEnvelope) {
            responseBuilder.header("X-NanoFaaS-Function-Status", "true");
            if (encoding != null) {
                responseBuilder.header("X-NanoFaaS-Encoding", encoding);
            }
        }
        if (isColdStart) {
            responseBuilder.header("X-Cold-Start", "true");
            responseBuilder.header("X-Init-Duration-Ms", String.valueOf(coldStartTracker.initDurationMs()));
        }
        // A handler Content-Type selects StringHttpMessageConverter.  The string stays JSON
        // (including quotes for scalar output), while the callback keeps the JsonNode envelope.
        boolean hasContentType = allowedHeaders.keySet().stream()
                .anyMatch("content-type"::equalsIgnoreCase);
        return responseBuilder.body(hasContentType ? output.toString() : output);
    }

    private void warnOnDroppedHeaders(Map<String, String> rawHeaders, Map<String, String> allowedHeaders,
                                       String executionId) {
        if (rawHeaders == null || rawHeaders.size() == allowedHeaders.size()) {
            return;
        }
        List<String> dropped = rawHeaders.keySet().stream()
                .filter(key -> !allowedHeaders.containsKey(key))
                .toList();
        if (!dropped.isEmpty()) {
            log.warn("Dropped response header(s) {} for execution {}",
                    singleLine(dropped), singleLine(executionId));
        }
    }

    private ResponseEntity<Object> handleHandlerFailure(Exception ex,
                                                        CallbackDispatcher.CallbackReservation callbackReservation,
                                                        String effectiveExecutionId,
                                                        String traceId, String dispatchAttempt) {
        String errorMessage = handlerErrorMessage(ex);
        log.error("Handler error for execution {}: {}",
                singleLine(effectiveExecutionId), singleLine(errorMessage), ex);

        submitCallback(callbackReservation,
                effectiveExecutionId,
                CallbackPayload.error("HANDLER_ERROR", errorMessage),
                traceId,
                dispatchAttempt);

        return ResponseEntity.status(500)
                .body(errorBody("HANDLER_ERROR", errorMessage));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        return Map.of(ERROR_KEY, Map.of("code", code, "message", message));
    }

    private CallbackDispatcher.SubmitResult submitCallback(CallbackDispatcher.CallbackReservation reservation,
                                                           String executionId, CallbackPayload payload,
                                                           String traceId, String dispatchAttempt) {
        if (reservation == null) {
            return callbackDispatcher.submit(executionId, payload, traceId, dispatchAttempt)
                    ? CallbackDispatcher.SubmitResult.ACCEPTED
                    : CallbackDispatcher.SubmitResult.SATURATED;
        }
        CallbackDispatcher.SubmitResult result = callbackDispatcher.submit(
                reservation, executionId, payload, traceId, dispatchAttempt);
        return result == null ? CallbackDispatcher.SubmitResult.SATURATED : result;
    }

    private static ResponseEntity<Object> callbackHandoffFailure(CallbackDispatcher.SubmitResult result) {
        if (result == CallbackDispatcher.SubmitResult.PAYLOAD_TOO_LARGE) {
            return ResponseEntity.status(500).body(errorBody(
                    "RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit"));
        }
        if (result == CallbackDispatcher.SubmitResult.SERIALIZATION_FAILED) {
            return ResponseEntity.status(500).body(errorBody(
                    "OUTPUT_SERIALIZATION_ERROR", "Function output is not JSON-serializable"));
        }
        return ResponseEntity.status(503).header("Retry-After", "1").body(errorBody(
                "RUNTIME_STOPPING", "Runtime is stopping"));
    }

    public ResponseEntity<Object> invoke(
            InvocationRequest request,
            String headerExecutionId,
            String traceId) {
        return invoke(request, headerExecutionId, traceId, null);
    }

    private static String handlerErrorMessage(Exception ex) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? DEFAULT_HANDLER_ERROR_MESSAGE : message;
    }
}
