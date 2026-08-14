package nanofaas

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"time"
)

const contentTypeHeader = "Content-Type"

func (r *Runtime) handleInvoke(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var request InvocationRequest
	if err := json.NewDecoder(req.Body).Decode(&request); err != nil {
		writeErrorJSON(w, http.StatusBadRequest, "Malformed request body")
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

	isColdStart := r.coldStart.FirstInvocation()
	if isColdStart {
		r.markColdStart()
	}
	r.coldStart.MarkFirstRequestArrival()

	ctx := WithInvocationContext(req.Context(), runtimeContext.ExecutionID, runtimeContext.TraceID)
	ctx, cancel := context.WithTimeout(ctx, r.settings.HandlerTimeout)
	defer cancel()

	type resultEnvelope struct {
		output any
		err    error
	}
	resultCh := make(chan resultEnvelope, 1)
	start := time.Now()
	go func() {
		defer func() {
			if recovered := recover(); recovered != nil {
				resultCh <- resultEnvelope{err: fmt.Errorf("handler panic: %v", recovered)}
			}
		}()
		output, err := handler(ctx, request)
		resultCh <- resultEnvelope{output: output, err: err}
	}()

	select {
	case result := <-resultCh:
		r.markHandlerDuration(time.Since(start).Seconds())
		r.handleInvokeResult(w, result.output, result.err, isColdStart, runtimeContext, dispatchAttempt)
	case <-ctx.Done():
		r.markHandlerDuration(time.Since(start).Seconds())
		r.handleInvokeTimeout(w, ctx, runtimeContext, dispatchAttempt)
	}
}

func (r *Runtime) handleInvokeResult(w http.ResponseWriter, output any, handlerErr error, isColdStart bool, runtimeContext InvocationContext, dispatchAttempt string) {
	if handlerErr != nil {
		r.markInvocation("error")
		r.submitCallback(runtimeContext, Failure("HANDLER_ERROR", handlerErr.Error()), dispatchAttempt)
		writeErrorJSON(w, http.StatusInternalServerError, handlerErr.Error())
		return
	}

	r.markInvocation("success")

	envelope, isEnvelope := output.(HandlerResponse)
	if isEnvelope && !IsStatusCodeValid(envelope.StatusCode) {
		message := fmt.Sprintf("Handler returned invalid statusCode: %d", envelope.StatusCode)
		log.Printf("WARN Handler returned invalid statusCode %d for execution %s, treating as platform error", envelope.StatusCode, runtimeContext.ExecutionID)
		r.submitCallback(runtimeContext, Failure("OUTPUT_SERIALIZATION_ERROR", message), dispatchAttempt)
		writeErrorJSON(w, http.StatusInternalServerError, message)
		return
	}

	status, outputForWire := r.buildInvokeResponse(w, output, isEnvelope, runtimeContext, dispatchAttempt)

	if isColdStart {
		w.Header().Set("X-Cold-Start", "true")
		w.Header().Set("X-Init-Duration-Ms", formatInitDurationHeader(r.coldStart.InitDurationMs()))
	}
	if w.Header().Get(contentTypeHeader) == "" {
		w.Header().Set(contentTypeHeader, "application/json")
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(outputForWire)
}

func (r *Runtime) buildInvokeResponse(w http.ResponseWriter, output any, isEnvelope bool, runtimeContext InvocationContext, dispatchAttempt string) (int, any) {
	if !isEnvelope {
		r.submitCallback(runtimeContext, Success(output), dispatchAttempt)
		return http.StatusOK, output
	}

	envelope := output.(HandlerResponse)
	allowed := FilterAllowedHeaders(envelope.Headers)
	if len(allowed) != len(envelope.Headers) {
		dropped := make([]string, 0, len(envelope.Headers))
		for key := range envelope.Headers {
			if _, kept := allowed[key]; !kept {
				dropped = append(dropped, key)
			}
		}
		log.Printf("WARN dropped response header(s) %v for execution %s", dropped, runtimeContext.ExecutionID)
	}
	for key, value := range allowed {
		w.Header().Set(key, value)
	}
	w.Header().Set("X-NanoFaaS-Function-Status", "true")
	if envelope.Encoding != "" {
		w.Header().Set("X-NanoFaaS-Encoding", envelope.Encoding)
	}
	r.submitCallback(runtimeContext, SuccessWithEnvelope(envelope.Output, envelope.StatusCode, allowed, envelope.Encoding), dispatchAttempt)
	return envelope.StatusCode, envelope.Output
}

func (r *Runtime) handleInvokeTimeout(w http.ResponseWriter, ctx context.Context, runtimeContext InvocationContext, dispatchAttempt string) {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		r.markInvocation("timeout")
		r.submitCallback(runtimeContext, Failure("HANDLER_TIMEOUT", "Handler exceeded configured timeout"), dispatchAttempt)
		writeErrorJSON(w, http.StatusGatewayTimeout, "Handler timed out")
		return
	}
	r.markInvocation("error")
	writeErrorJSON(w, http.StatusInternalServerError, "Handler execution cancelled")
}

func (r *Runtime) submitCallback(runtimeContext InvocationContext, result InvocationResult, dispatchAttempt string) {
	if ok := r.callbackDispatcher.SubmitWithDispatchAttempt(context.Background(), runtimeContext.ExecutionID, result, runtimeContext.TraceID, dispatchAttempt); !ok {
		r.markCallbackDrop()
	}
}

func writeErrorJSON(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set(contentTypeHeader, "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
