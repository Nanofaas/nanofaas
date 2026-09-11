package nanofaas

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestCanonicalTerminalCallbacksFitReservedMinimum(t *testing.T) {
	results := []InvocationResult{
		Failure("HANDLER_ERROR", "Handler failed"),
		Failure("OUTPUT_SERIALIZATION_ERROR", fmt.Sprintf("Handler returned invalid statusCode: %d", -int(^uint(0)>>1)-1)),
		Failure("RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit"),
		Failure("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
		Failure("INVOCATION_CANCELLED", "Invocation cancelled"),
	}
	for _, result := range results {
		if _, err := encodeJSONBounded(result, minimumTerminalCallbackPayloadBytes); err != nil {
			t.Fatalf("canonical terminal callback exceeds %d bytes: %+v: %v",
				minimumTerminalCallbackPayloadBytes, result, err)
		}
	}
}

func TestInvokeRejectsInputOverLimitBeforeHandler(t *testing.T) {
	var called atomic.Bool
	rt := newLimitedTestRuntime(RuntimeSettings{MaxInputBytes: 16})
	rt.Register("never", func(context.Context, InvocationRequest) (any, error) {
		called.Store(true)
		return "unexpected", nil
	})

	rec := invokeRequest(rt, strings.NewReader(`{"input":"0123456789"}`))

	assertRuntimeError(t, rec, http.StatusRequestEntityTooLarge,
		"RUNTIME_INPUT_TOO_LARGE", "Runtime input exceeds configured byte limit")
	if called.Load() {
		t.Fatal("oversized input reached handler")
	}
}

func TestInvokeRejectsOutputOverLimitWithCanonicalError(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{MaxOutputBytes: 8})
	rt.Register("large", func(context.Context, InvocationRequest) (any, error) {
		return "0123456789", nil
	})

	rec := invokeRequest(rt, strings.NewReader(`{"input":null}`))

	assertRuntimeError(t, rec, http.StatusInternalServerError,
		"RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit")
}

func TestInvokeReportsUnsupportedJSONAsSerializationError(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.Register("unsupported", func(context.Context, InvocationRequest) (any, error) {
		return func() {}, nil
	})

	rec := invokeRequest(rt, strings.NewReader(`{"input":null}`))

	assertRuntimeError(t, rec, http.StatusInternalServerError,
		"OUTPUT_SERIALIZATION_ERROR", "Handler output could not be serialized")
}

func TestInvokeHandlerFailureUsesCanonicalPlatformError(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.Register("failure", func(context.Context, InvocationRequest) (any, error) {
		return nil, io.ErrUnexpectedEOF
	})

	rec := invokeRequest(rt, strings.NewReader(`{"input":null}`))

	assertRuntimeError(t, rec, http.StatusInternalServerError, "HANDLER_ERROR", "Handler failed")
}

func TestInvokeRejectsCallbackSaturationBeforeHandler(t *testing.T) {
	var called atomic.Bool
	rt := newLimitedTestRuntime(RuntimeSettings{
		MaxPendingCallbacks: 1, MaxPendingCallbackBytes: minimumTerminalCallbackPayloadBytes,
		MaxCallbackPayloadBytes: minimumTerminalCallbackPayloadBytes,
	})
	reservation := rt.callbackDispatcher.TryReserve()
	if reservation == nil {
		t.Fatal("failed to occupy callback capacity")
	}
	defer reservation.Release()
	rt.Register("never", func(context.Context, InvocationRequest) (any, error) {
		called.Store(true)
		return "unexpected", nil
	})

	rec := invokeRequest(rt, strings.NewReader(`{"input":null}`))

	assertRuntimeError(t, rec, http.StatusTooManyRequests,
		"RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted")
	if rec.Header().Get("Retry-After") != "1" {
		t.Fatalf("missing Retry-After: %v", rec.Header())
	}
	if called.Load() {
		t.Fatal("callback-saturated invocation reached handler")
	}
}

func TestInvokeRejectsCallbackCapTooSmallForCanonicalTerminalBeforeHandler(t *testing.T) {
	var called atomic.Bool
	rt := newLimitedTestRuntime(RuntimeSettings{MaxPendingCallbackBytes: 64, MaxCallbackPayloadBytes: 64})
	rt.Register("never", func(context.Context, InvocationRequest) (any, error) {
		called.Store(true)
		return "unexpected", nil
	})

	rec := invokeRequest(rt, strings.NewReader(`{"input":null}`))

	assertRuntimeError(t, rec, http.StatusTooManyRequests,
		"RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted")
	if called.Load() {
		t.Fatal("runtime started a handler without room for a canonical terminal callback")
	}
}

func TestInvokeKeepsHandlerSaturatedUntilTimedOutWorkPhysicallyStops(t *testing.T) {
	block := make(chan struct{})
	started := make(chan struct{})
	rt := newLimitedTestRuntime(RuntimeSettings{MaxConcurrentHandlers: 1, HandlerTimeout: 20 * time.Millisecond})
	rt.Register("blocking", func(context.Context, InvocationRequest) (any, error) {
		select {
		case <-started:
		default:
			close(started)
		}
		<-block
		return "done", nil
	})

	firstDone := make(chan *httptest.ResponseRecorder, 1)
	go func() { firstDone <- invokeRequest(rt, strings.NewReader(`{"input":null}`)) }()
	<-started
	first := <-firstDone
	assertRuntimeError(t, first, http.StatusGatewayTimeout, "HANDLER_TIMEOUT", "Handler exceeded configured timeout")

	second := invokeRequest(rt, strings.NewReader(`{"input":null}`))
	assertRuntimeError(t, second, http.StatusTooManyRequests,
		"RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted")
	if second.Header().Get("Retry-After") != "1" {
		t.Fatal("handler saturation must be retryable")
	}

	close(block)
	deadline := time.After(time.Second)
	for rt.limits.snapshot().activeHandlers != 0 {
		select {
		case <-deadline:
			t.Fatal("physical handler permit did not drain")
		case <-rt.limits.changedSignal():
		}
	}
}

func TestInvokeRejectsWhileRuntimeStopping(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.Register("never", func(context.Context, InvocationRequest) (any, error) { return "unexpected", nil })
	rt.limits.stopAdmission()

	rec := invokeRequest(rt, strings.NewReader(`{"input":null}`))

	assertRuntimeError(t, rec, http.StatusServiceUnavailable, "RUNTIME_STOPPING", "Runtime is stopping")
	if rec.Header().Get("Retry-After") != "1" {
		t.Fatal("stopping response must be retryable")
	}
}

func TestHealthRemainsAvailableWhileInvokeCapacityIsSaturated(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{MaxConcurrentHandlers: 1, MaxPendingCallbacks: 1})
	handlerReservation := rt.limits.tryReserveHandler()
	callbackReservation := rt.callbackDispatcher.TryReserve()
	if handlerReservation == nil || callbackReservation == nil {
		t.Fatal("failed to saturate invoke capacity")
	}
	defer handlerReservation.release()
	defer callbackReservation.Release()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	rt.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK || strings.TrimSpace(rec.Body.String()) != `{"status":"ok"}` {
		t.Fatalf("health failed under saturation: %d %s", rec.Code, rec.Body.String())
	}
}

type blockingBody struct {
	closed chan struct{}
}

func (b *blockingBody) Read([]byte) (int, error) {
	<-b.closed
	return 0, io.ErrClosedPipe
}

func (b *blockingBody) Close() error {
	select {
	case <-b.closed:
	default:
		close(b.closed)
	}
	return nil
}

func TestInvokeBodyReadHasFiniteRuntimeDeadline(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{BodyReadTimeout: 20 * time.Millisecond})
	rt.Register("never", func(context.Context, InvocationRequest) (any, error) { return nil, nil })
	body := &blockingBody{closed: make(chan struct{})}

	start := time.Now()
	rec := invokeRequest(rt, body)

	assertRuntimeError(t, rec, http.StatusRequestTimeout,
		"RUNTIME_BODY_READ_TIMEOUT", "Runtime request body read timed out")
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Fatalf("body read deadline was not finite: %s", elapsed)
	}
}

func TestInvokeTracksInputUntilPhysicalHandlerCompletion(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.Register("blocking", func(context.Context, InvocationRequest) (any, error) {
		close(started)
		<-release
		return "ok", nil
	})
	done := make(chan struct{})
	go func() {
		invokeRequest(rt, strings.NewReader(`{"input":"retained"}`))
		close(done)
	}()
	<-started
	if got := rt.limits.snapshot().inputBytes; got == 0 {
		t.Fatal("active handler input bytes were not tracked")
	}
	close(release)
	<-done
	// The response can finish before the handler goroutine runs its deferred
	// release. Observe physical completion before asserting drained accounting.
	drainCtx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := rt.limits.waitForHandlers(drainCtx); err != nil {
		t.Fatalf("physical handler did not drain: %v", err)
	}
	if got := rt.limits.snapshot().inputBytes; got != 0 {
		t.Fatalf("input bytes did not drain: %d", got)
	}
}

type blockingResponseWriter struct {
	header  http.Header
	started chan struct{}
	release chan struct{}
}

func (w *blockingResponseWriter) Header() http.Header { return w.header }
func (w *blockingResponseWriter) WriteHeader(int)     {}
func (w *blockingResponseWriter) Write(body []byte) (int, error) {
	close(w.started)
	<-w.release
	return len(body), nil
}

func TestInvokeTracksOutputUntilResponseWriteCompletes(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.Register("output", func(context.Context, InvocationRequest) (any, error) { return "retained", nil })
	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":null}`))
	req.Header.Set("X-Execution-Id", "exec-1")
	w := &blockingResponseWriter{header: make(http.Header), started: make(chan struct{}), release: make(chan struct{})}
	done := make(chan struct{})
	go func() {
		rt.Handler().ServeHTTP(w, req)
		close(done)
	}()
	<-w.started
	if got := rt.limits.snapshot().outputBytes; got == 0 {
		t.Fatal("serialized output bytes were not tracked while retained")
	}
	close(w.release)
	<-done
	if got := rt.limits.snapshot().outputBytes; got != 0 {
		t.Fatalf("output bytes did not drain: %d", got)
	}
}

func TestInvokeCancellationRequestsHandlerStopAndEmitsCanonicalCallback(t *testing.T) {
	callbackBody := make(chan []byte, 1)
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.callbackClient.httpClient = &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		body, err := io.ReadAll(req.Body)
		if err != nil {
			t.Errorf("read callback: %v", err)
		}
		callbackBody <- body
		return &http.Response{StatusCode: http.StatusNoContent, Body: http.NoBody}, nil
	})}
	started := make(chan struct{})
	rt.Register("cancel", func(ctx context.Context, _ InvocationRequest) (any, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	})
	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest(http.MethodPost, "/invoke", strings.NewReader(`{"input":null}`)).WithContext(ctx)
	req.Header.Set("X-Execution-Id", "exec-cancel")
	req.Header.Set("X-Dispatch-Attempt", "2")
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		rt.Handler().ServeHTTP(rec, req)
		close(done)
	}()
	<-started
	cancel()
	<-done
	if rec.Body.Len() != 0 {
		t.Fatalf("cancelled client received a response body: %s", rec.Body.String())
	}
	select {
	case body := <-callbackBody:
		var result InvocationResult
		if err := json.Unmarshal(body, &result); err != nil {
			t.Fatal(err)
		}
		if result.Error == nil || result.Error.Code != "INVOCATION_CANCELLED" || result.Error.Message != "Invocation cancelled" {
			t.Fatalf("unexpected cancellation callback: %+v", result)
		}
	case <-time.After(time.Second):
		t.Fatal("cancellation callback was not delivered")
	}
	waitForDispatcherDrain(t, rt.callbackDispatcher)
	drainCtx, drainCancel := context.WithTimeout(context.Background(), time.Second)
	defer drainCancel()
	if err := rt.limits.waitForHandlers(drainCtx); err != nil {
		t.Fatalf("cancelled handler did not physically drain: %v", err)
	}
}

func TestCallbackDeliveryExhaustionIsCountedAndReleasesResources(t *testing.T) {
	attempted := make(chan struct{}, 3)
	rt := newLimitedTestRuntime(RuntimeSettings{CallbackMaxAttempts: 3})
	rt.callbackClient.retryDelays = []int{0, 0, 0}
	rt.callbackClient.httpClient = &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		attempted <- struct{}{}
		return &http.Response{StatusCode: http.StatusServiceUnavailable, Body: http.NoBody}, nil
	})}
	rt.Register("success", func(context.Context, InvocationRequest) (any, error) {
		return map[string]string{"result": "ok"}, nil
	})

	response := invokeRequest(rt, strings.NewReader(`{"input":null}`))
	if response.Code != http.StatusOK {
		t.Fatalf("invoke failed before callback exhaustion: %d %s", response.Code, response.Body.String())
	}
	for range 3 {
		select {
		case <-attempted:
		case <-time.After(time.Second):
			t.Fatal("callback retries did not exhaust")
		}
	}
	waitForDispatcherDrain(t, rt.callbackDispatcher)
	families, err := rt.metrics.registry.Gather()
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, family := range families {
		if family.GetName() == "nanofaas_runtime_callback_drops_total" &&
			len(family.Metric) == 1 && family.Metric[0].GetCounter().GetValue() == 1 {
			found = true
		}
	}
	if !found {
		t.Fatal("callback delivery exhaustion metric was not incremented")
	}
	assertDispatcherDrained(t, rt.callbackDispatcher)
}

func TestInvokeReturnsStoppingWhenDispatcherClosesAfterAdmission(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	rt := newLimitedTestRuntime(RuntimeSettings{})
	rt.Register("stop-race", func(context.Context, InvocationRequest) (any, error) {
		close(started)
		<-release
		return "late", nil
	})
	response := make(chan *httptest.ResponseRecorder, 1)
	go func() { response <- invokeRequest(rt, strings.NewReader(`{"input":null}`)) }()
	<-started
	if err := rt.callbackDispatcher.Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	close(release)
	rec := <-response
	assertRuntimeError(t, rec, http.StatusServiceUnavailable, "RUNTIME_STOPPING", "Runtime is stopping")
	if rec.Header().Get("Retry-After") != "1" {
		t.Fatal("stop race must be retryable")
	}
	assertDispatcherDrained(t, rt.callbackDispatcher)
}

func newLimitedTestRuntime(overrides RuntimeSettings) *Runtime {
	settings := RuntimeSettings{
		ExecutionID:             "exec-1",
		CallbackURL:             "http://callback/v1/internal/executions",
		HandlerTimeout:          time.Second,
		MaxConcurrentHandlers:   2,
		MaxInputBytes:           1024,
		MaxOutputBytes:          1024,
		MaxPendingCallbacks:     4,
		MaxPendingCallbackBytes: 4096,
		MaxCallbackPayloadBytes: 1024,
		BodyReadTimeout:         time.Second,
		CallbackAttemptTimeout:  time.Second,
		CallbackMaxAttempts:     1,
		ShutdownTimeout:         time.Second,
	}
	mergeRuntimeSettings(&settings, overrides)
	return NewRuntime(WithSettings(settings))
}

func invokeRequest(rt *Runtime, body io.Reader) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/invoke", body)
	req.Header.Set("X-Execution-Id", "exec-1")
	rec := httptest.NewRecorder()
	rt.Handler().ServeHTTP(rec, req)
	return rec
}

func assertRuntimeError(t *testing.T, rec *httptest.ResponseRecorder, status int, code, message string) {
	t.Helper()
	if rec.Code != status {
		t.Fatalf("unexpected status %d body=%s", rec.Code, rec.Body.String())
	}
	var body struct {
		Error ErrorInfo `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("invalid error JSON: %v body=%q", err, rec.Body.String())
	}
	if body.Error.Code != code || body.Error.Message != message {
		t.Fatalf("unexpected error: %+v", body.Error)
	}
}
