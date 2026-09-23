package nanofaas

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"
)

const contentTypeHeader = "Content-Type"

func (r *Runtime) handleInvoke(w http.ResponseWriter, req *http.Request) {
	if r.rejectBeforeAdmission(w, req) {
		return
	}

	runtimeContext := r.settings.ResolveInvocationContext(req.Header.Get("X-Execution-Id"), req.Header.Get("X-Trace-Id"))
	dispatchAttempt := req.Header.Get("X-Dispatch-Attempt")
	if runtimeContext.ExecutionID == "" {
		writeErrorJSON(w, http.StatusBadRequest, "Execution ID not configured")
		return
	}
	handler, err := r.ResolveHandler()
	if err != nil {
		writeErrorJSON(w, http.StatusInternalServerError, "Handler not configured")
		return
	}
	if r.rejectSaturated(w) {
		return
	}
	callbackReservation := r.callbackDispatcher.TryReserve()
	if callbackReservation == nil {
		writeRetryableRuntimeError(w, http.StatusTooManyRequests,
			"RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted")
		return
	}
	callbackOwned := true
	defer func() {
		if callbackOwned {
			callbackReservation.Release()
		}
	}()

	handlerReservation := r.limits.tryReserveHandler()
	if handlerReservation == nil {
		r.writeHandlerUnavailable(w)
		return
	}

	request, inputBytes, readErr := r.readInvocationRequest(req)
	if readErr != nil {
		handlerReservation.release()
		writeReadError(w, readErr)
		return
	}
	handlerReservation.retainInput(inputBytes)

	isColdStart := r.coldStart.FirstInvocation()
	if isColdStart {
		r.markColdStart()
	}
	r.coldStart.MarkFirstRequestArrival()
	ctx := WithInvocationContext(req.Context(), runtimeContext.ExecutionID, runtimeContext.TraceID)
	ctx, cancel := context.WithTimeout(ctx, r.settings.HandlerTimeout)
	defer cancel()

	resultCh := make(chan handlerOutcome)
	start := time.Now()
	go runHandler(ctx, handler, request, handlerReservation, resultCh)
	// Only the handler goroutine keeps the input; it drops it once the handler returns.
	request = InvocationRequest{}

	select {
	case result := <-resultCh:
		r.markHandlerDuration(time.Since(start).Seconds())
		callbackOwned = !r.handleInvokeResult(w, result.output, result.err, isColdStart,
			runtimeContext, dispatchAttempt, callbackReservation)
	case <-ctx.Done():
		r.markHandlerDuration(time.Since(start).Seconds())
		callbackOwned = !r.handleInvokeTimeout(w, ctx, runtimeContext, dispatchAttempt, callbackReservation)
	}
}

type handlerOutcome struct {
	output any
	err    error
}

// runHandler runs one invocation, turning a panic into an error, and returns its handler
// permit when the handler really ends; the result is dropped if the caller stopped waiting.
func runHandler(ctx context.Context, handler Handler, request InvocationRequest,
	reservation *handlerReservation, resultCh chan<- handlerOutcome) {
	defer reservation.release()
	result := callHandler(ctx, handler, request)
	request = InvocationRequest{} // release the input before waiting for the caller
	select {
	case resultCh <- result:
	case <-ctx.Done():
	}
}

func callHandler(ctx context.Context, handler Handler, request InvocationRequest) (result handlerOutcome) {
	defer func() {
		if recovered := recover(); recovered != nil {
			result.err = fmt.Errorf("handler panic: %v", recovered)
		}
	}()
	result.output, result.err = handler(ctx, request)
	return result
}

func (r *Runtime) rejectBeforeAdmission(w http.ResponseWriter, req *http.Request) bool {
	if req.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return true
	}
	if !r.limits.snapshot().accepting {
		writeStopping(w)
		return true
	}
	return false
}

// rejectSaturated reports occupied handler capacity even when its terminal callback reserve
// also fills the callback budget. This read-only rejection takes no permit: accepted work
// still reserves callbacks before atomically reserving a handler.
func (r *Runtime) rejectSaturated(w http.ResponseWriter) bool {
	if r.limits.snapshot().activeHandlers >= r.settings.MaxConcurrentHandlers {
		writeRetryableRuntimeError(w, http.StatusTooManyRequests,
			"RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted")
		return true
	}
	if r.settings.MaxCallbackPayloadBytes < minimumTerminalCallbackPayloadBytes {
		writeRetryableRuntimeError(w, http.StatusTooManyRequests,
			"RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted")
		return true
	}
	return false
}

func (r *Runtime) writeHandlerUnavailable(w http.ResponseWriter) {
	if !r.limits.snapshot().accepting {
		writeStopping(w)
		return
	}
	writeRetryableRuntimeError(w, http.StatusTooManyRequests,
		"RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted")
}

func writeReadError(w http.ResponseWriter, readErr error) {
	switch {
	case errors.Is(readErr, errPayloadTooLarge):
		writeRuntimeError(w, http.StatusRequestEntityTooLarge,
			"RUNTIME_INPUT_TOO_LARGE", "Runtime input exceeds configured byte limit")
	case errors.Is(readErr, context.DeadlineExceeded):
		writeRuntimeError(w, http.StatusRequestTimeout,
			"RUNTIME_BODY_READ_TIMEOUT", "Runtime request body read timed out")
	case errors.Is(readErr, context.Canceled):
		// The client is gone: there is nobody to answer.
	default:
		writeErrorJSON(w, http.StatusBadRequest, "Malformed request body")
	}
}

func writeStopping(w http.ResponseWriter) {
	writeRetryableRuntimeError(w, http.StatusServiceUnavailable, "RUNTIME_STOPPING", "Runtime is stopping")
}

func (r *Runtime) readInvocationRequest(req *http.Request) (InvocationRequest, int64, error) {
	if req.ContentLength > r.settings.MaxInputBytes {
		return InvocationRequest{}, 0, errPayloadTooLarge
	}
	ctx, cancel := context.WithTimeout(req.Context(), r.settings.BodyReadTimeout)
	defer cancel()
	stopClose := context.AfterFunc(ctx, func() { _ = req.Body.Close() })
	defer stopClose()
	body, err := io.ReadAll(io.LimitReader(req.Body, r.settings.MaxInputBytes+1))
	if ctx.Err() != nil {
		return InvocationRequest{}, 0, ctx.Err()
	}
	if err != nil {
		return InvocationRequest{}, 0, err
	}
	if int64(len(body)) > r.settings.MaxInputBytes {
		return InvocationRequest{}, 0, errPayloadTooLarge
	}
	var request InvocationRequest
	if err := json.Unmarshal(body, &request); err != nil {
		return InvocationRequest{}, 0, err
	}
	return request, int64(len(body)), nil
}

func (r *Runtime) handleInvokeResult(w http.ResponseWriter, output any, handlerErr error, isColdStart bool,
	runtimeContext InvocationContext, dispatchAttempt string, callbackReservation *CallbackReservation) bool {
	if handlerErr != nil {
		r.markInvocation("error")
		log.Printf("ERROR handler failed for execution %s: %v", runtimeContext.ExecutionID, handlerErr)
		r.submitReservedCallback(callbackReservation, runtimeContext,
			Failure("HANDLER_ERROR", "Handler failed"), dispatchAttempt)
		writeRuntimeError(w, http.StatusInternalServerError, "HANDLER_ERROR", "Handler failed")
		return true
	}

	envelope, isEnvelope := output.(HandlerResponse)
	if isEnvelope && !IsStatusCodeValid(envelope.StatusCode) {
		message := fmt.Sprintf("Handler returned invalid statusCode: %d", envelope.StatusCode)
		log.Printf("WARN Handler returned invalid statusCode %d for execution %s, treating as platform error", envelope.StatusCode, runtimeContext.ExecutionID)
		r.submitReservedCallback(callbackReservation, runtimeContext,
			Failure("OUTPUT_SERIALIZATION_ERROR", message), dispatchAttempt)
		writeRuntimeError(w, http.StatusInternalServerError, "OUTPUT_SERIALIZATION_ERROR", message)
		return true
	}

	outputForWire := output
	callbackResult := Success(output)
	status := http.StatusOK
	if isEnvelope {
		outputForWire, status, callbackResult = applyEnvelope(w, envelope)
	}

	body, err := encodeJSONBounded(outputForWire, r.settings.MaxOutputBytes)
	if err != nil {
		if errors.Is(err, errPayloadTooLarge) {
			r.handleOversizedOutput(w, callbackReservation, runtimeContext, dispatchAttempt)
		} else {
			r.handleOutputSerializationError(w, callbackReservation, runtimeContext, dispatchAttempt)
		}
		return true
	}
	if err := r.callbackDispatcher.SubmitReserved(context.Background(), callbackReservation,
		runtimeContext.ExecutionID, callbackResult, runtimeContext.TraceID, dispatchAttempt); err != nil {
		return r.handleCallbackSubmitFailure(w, err, callbackReservation, runtimeContext, dispatchAttempt)
	}

	r.markInvocation("success")
	r.setSuccessHeaders(w, isColdStart)
	w.WriteHeader(status)
	releaseOutput := r.limits.retainOutput(int64(len(body)))
	defer releaseOutput()
	_, _ = w.Write(body)
	return true
}

// applyEnvelope writes a HandlerResponse's allowed headers and returns its wire output,
// status and callback result.
func applyEnvelope(w http.ResponseWriter, envelope HandlerResponse) (any, int, InvocationResult) {
	allowed := FilterAllowedHeaders(envelope.Headers)
	for key, value := range allowed {
		w.Header().Set(key, value)
	}
	w.Header().Set("X-NanoFaaS-Function-Status", "true")
	if envelope.Encoding != "" {
		w.Header().Set("X-NanoFaaS-Encoding", envelope.Encoding)
	}
	return envelope.Output, envelope.StatusCode,
		SuccessWithEnvelope(envelope.Output, envelope.StatusCode, allowed, envelope.Encoding)
}

// handleCallbackSubmitFailure answers a failed callback hand-off; it reports whether the
// reservation was consumed.
func (r *Runtime) handleCallbackSubmitFailure(w http.ResponseWriter, err error, callbackReservation *CallbackReservation,
	runtimeContext InvocationContext, dispatchAttempt string) bool {
	if errors.Is(err, errPayloadTooLarge) {
		r.handleOversizedOutput(w, callbackReservation, runtimeContext, dispatchAttempt)
		return true
	}
	if errors.Is(err, errJSONSerialization) {
		r.handleOutputSerializationError(w, callbackReservation, runtimeContext, dispatchAttempt)
		return true
	}
	r.markCallbackDrop()
	writeStopping(w)
	return false
}

func (r *Runtime) setSuccessHeaders(w http.ResponseWriter, isColdStart bool) {
	if isColdStart {
		w.Header().Set("X-Cold-Start", "true")
		w.Header().Set("X-Init-Duration-Ms", formatInitDurationHeader(r.coldStart.InitDurationMs()))
	}
	if w.Header().Get(contentTypeHeader) == "" {
		w.Header().Set(contentTypeHeader, "application/json")
	}
}

func (r *Runtime) handleOutputSerializationError(w http.ResponseWriter, reservation *CallbackReservation,
	runtimeContext InvocationContext, dispatchAttempt string) {
	r.markInvocation("error")
	r.submitReservedCallback(reservation, runtimeContext,
		Failure("OUTPUT_SERIALIZATION_ERROR", "Handler output could not be serialized"), dispatchAttempt)
	writeRuntimeError(w, http.StatusInternalServerError,
		"OUTPUT_SERIALIZATION_ERROR", "Handler output could not be serialized")
}

func (r *Runtime) handleOversizedOutput(w http.ResponseWriter, reservation *CallbackReservation,
	runtimeContext InvocationContext, dispatchAttempt string) {
	r.markInvocation("error")
	r.submitReservedCallback(reservation, runtimeContext,
		Failure("RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit"), dispatchAttempt)
	writeRuntimeError(w, http.StatusInternalServerError,
		"RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit")
}

func (r *Runtime) handleInvokeTimeout(w http.ResponseWriter, ctx context.Context, runtimeContext InvocationContext,
	dispatchAttempt string, callbackReservation *CallbackReservation) bool {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		r.markInvocation("timeout")
		r.submitReservedCallback(callbackReservation, runtimeContext,
			Failure("HANDLER_TIMEOUT", "Handler exceeded configured timeout"), dispatchAttempt)
		writeRuntimeError(w, http.StatusGatewayTimeout, "HANDLER_TIMEOUT", "Handler exceeded configured timeout")
		return true
	}
	r.markInvocation("error")
	r.submitReservedCallback(callbackReservation, runtimeContext,
		Failure("INVOCATION_CANCELLED", "Invocation cancelled"), dispatchAttempt)
	return true
}

func (r *Runtime) submitReservedCallback(reservation *CallbackReservation, runtimeContext InvocationContext,
	result InvocationResult, dispatchAttempt string) {
	if err := r.callbackDispatcher.SubmitReserved(context.Background(), reservation,
		runtimeContext.ExecutionID, result, runtimeContext.TraceID, dispatchAttempt); err != nil {
		r.markCallbackDrop()
		reservation.Release()
	}
}

func (r *Runtime) submitCallback(runtimeContext InvocationContext, result InvocationResult, dispatchAttempt string) {
	if ok := r.callbackDispatcher.SubmitWithDispatchAttempt(context.Background(), runtimeContext.ExecutionID,
		result, runtimeContext.TraceID, dispatchAttempt); !ok {
		r.markCallbackDrop()
	}
}

func writeErrorJSON(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeRuntimeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": ErrorInfo{Code: code, Message: message}})
}

func writeRetryableRuntimeError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Retry-After", "1")
	writeRuntimeError(w, status, code, message)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set(contentTypeHeader, "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
