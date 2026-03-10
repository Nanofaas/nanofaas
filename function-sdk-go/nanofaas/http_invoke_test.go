package nanofaas

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestInvokeReturnsHandlerOutputAndExecutionHeaders(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: time.Second}))
	rt.Register("echo", func(ctx context.Context, req InvocationRequest) (any, error) {
		return map[string]any{"echo": req.Input}, nil
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Execution-Id", "exec-1")
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("unexpected status %d", rec.Code)
	}
	if rec.Header().Get("X-Cold-Start") != "true" {
		t.Fatalf("expected cold start header")
	}

	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to decode body: %v", err)
	}
	if body["echo"] != "hi" {
		t.Fatalf("unexpected body: %+v", body)
	}
}

func TestInvokeUsesEnvironmentExecutionIDWhenHeaderMissing(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: time.Second}))
	rt.Register("echo", func(ctx context.Context, req InvocationRequest) (any, error) {
		executionID, ok := ExecutionIDFromContext(ctx)
		if !ok {
			t.Fatal("expected execution id in context")
		}
		return map[string]any{"executionId": executionID}, nil
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("unexpected status %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "env-exec") {
		t.Fatalf("expected env execution id in body: %s", rec.Body.String())
	}
}

func TestInvokeReturnsBadRequestWhenExecutionIDMissing(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{HandlerTimeout: time.Second}))
	rt.Register("echo", func(ctx context.Context, req InvocationRequest) (any, error) { return req.Input, nil })

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status %d", rec.Code)
	}
}

func TestInvokeReturnsBadRequestOnMalformedJSON(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: time.Second}))
	rt.Register("echo", func(ctx context.Context, req InvocationRequest) (any, error) { return req.Input, nil })

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":`))
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status %d", rec.Code)
	}
}

func TestInvokeRejectsNonPostMethods(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: time.Second}))
	rt.Register("echo", func(ctx context.Context, req InvocationRequest) (any, error) { return req.Input, nil })

	req := httptest.NewRequest(http.MethodGet, "/invoke", nil)
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("unexpected status %d", rec.Code)
	}
}

func TestInvokeReturnsInternalServerErrorOnHandlerError(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: time.Second}))
	rt.Register("boom", func(ctx context.Context, req InvocationRequest) (any, error) {
		return nil, errors.New("boom")
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("unexpected status %d", rec.Code)
	}
}

func TestInvokeErrorResponseIsValidJSON(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: time.Second}))
	rt.Register("boom", func(ctx context.Context, req InvocationRequest) (any, error) {
		return nil, errors.New("bad \"quote\"\nnewline")
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("unexpected status %d", rec.Code)
	}

	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("response body is not valid JSON: %v; body=%q", err, rec.Body.String())
	}
	if body["error"] != "bad \"quote\"\nnewline" {
		t.Fatalf("unexpected error body: %+v", body)
	}
}

func TestInvokeReturnsGatewayTimeoutOnHandlerTimeout(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{ExecutionID: "env-exec", HandlerTimeout: 10 * time.Millisecond}))
	rt.Register("slow", func(ctx context.Context, req InvocationRequest) (any, error) {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(100 * time.Millisecond):
			return "late", nil
		}
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusGatewayTimeout {
		t.Fatalf("unexpected status %d", rec.Code)
	}
}

func TestInvokeRecoversFromHandlerPanicAndReportsStructuredError(t *testing.T) {
	var callbackCount atomic.Int32
	callbackDone := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		callbackCount.Add(1)
		if r.URL.Path != "/v1/internal/executions/exec-1:complete" {
			t.Fatalf("unexpected callback path %s", r.URL.Path)
		}
		defer func() { callbackDone <- struct{}{} }()
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	rt := NewRuntime(WithSettings(RuntimeSettings{
		ExecutionID:    "env-exec",
		HandlerTimeout: time.Second,
		CallbackURL:    server.URL + "/v1/internal/executions",
	}))
	rt.callbackClient = NewCallbackClient(server.URL + "/v1/internal/executions")
	rt.callbackDispatcher = NewCallbackDispatcher(rt.callbackClient, 1, 4)
	defer func() { _ = rt.callbackDispatcher.Shutdown(context.Background()) }()
	rt.Register("panic", func(ctx context.Context, req InvocationRequest) (any, error) {
		panic("kaboom")
	})

	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":"hi"}`))
	req.Header.Set("X-Execution-Id", "exec-1")
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("unexpected status %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "kaboom") {
		t.Fatalf("unexpected body %s", rec.Body.String())
	}

	select {
	case <-callbackDone:
	case <-time.After(2 * time.Second):
		t.Fatal("expected callback after recovered panic")
	}
	if callbackCount.Load() != 1 {
		t.Fatalf("expected one callback, got %d", callbackCount.Load())
	}
}
