package nanofaas

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"
)

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
		if result.err != nil {
			r.markInvocation("error")
			r.submitCallback(runtimeContext, Failure("HANDLER_ERROR", result.err.Error()))
			writeErrorJSON(w, http.StatusInternalServerError, result.err.Error())
			return
		}

		r.markInvocation("success")
		r.submitCallback(runtimeContext, Success(result.output))
		if isColdStart {
			w.Header().Set("X-Cold-Start", "true")
			w.Header().Set("X-Init-Duration-Ms", formatInitDurationHeader(r.coldStart.InitDurationMs()))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(result.output)
	case <-ctx.Done():
		r.markHandlerDuration(time.Since(start).Seconds())
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			r.markInvocation("timeout")
			r.submitCallback(runtimeContext, Failure("HANDLER_TIMEOUT", "Handler exceeded configured timeout"))
			writeErrorJSON(w, http.StatusGatewayTimeout, "Handler timed out")
			return
		}
		r.markInvocation("error")
		writeErrorJSON(w, http.StatusInternalServerError, "Handler execution cancelled")
	}
}

func (r *Runtime) submitCallback(runtimeContext InvocationContext, result InvocationResult) {
	if ok := r.callbackDispatcher.Submit(context.Background(), runtimeContext.ExecutionID, result, runtimeContext.TraceID); !ok {
		r.markCallbackDrop()
	}
}

func writeErrorJSON(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
