package nanofaas

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestRuntimeExposesHealthAndMetricsEndpoints(t *testing.T) {
	rt := NewRuntime()
	srv := httptest.NewServer(rt.Handler())
	defer srv.Close()

	healthResp, err := http.Get(srv.URL + "/health")
	if err != nil || healthResp.StatusCode != http.StatusOK {
		t.Fatalf("health failed: %v status=%v", err, healthResp.StatusCode)
	}

	metricsResp, err := http.Get(srv.URL + "/metrics")
	if err != nil || metricsResp.StatusCode != http.StatusOK {
		t.Fatalf("metrics failed: %v status=%v", err, metricsResp.StatusCode)
	}
}

func TestRuntimeStartCleansOwnedDispatcherAfterBindFailure(t *testing.T) {
	// Occupy the same wildcard address Start uses. An IPv4-only listener may
	// leave the IPv6 wildcard bind available on macOS and hang this test.
	listener, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	rt := NewRuntime(WithSettings(RuntimeSettings{Port: strconv.Itoa(port), ShutdownTimeout: 100 * time.Millisecond}))

	if err := rt.Start(context.Background()); err == nil {
		t.Fatal("expected bind failure")
	}
	if reservation := rt.callbackDispatcher.TryReserve(); reservation != nil {
		reservation.Release()
		t.Fatal("bind failure left callback dispatcher accepting work")
	}
}

func TestRuntimeStopRejectsAdmissionAndSameInstanceRestarts(t *testing.T) {
	rt := newLimitedTestRuntime(RuntimeSettings{Port: "0", ShutdownTimeout: 250 * time.Millisecond})
	rt.Register("echo", func(_ context.Context, request InvocationRequest) (any, error) { return request.Input, nil })

	firstCancel, firstResult := startRuntimeForTest(t, rt)
	firstCancel()
	waitForRuntimeState(t, rt, runtimeStateStopping)
	stopping := invokeRequest(rt, strings.NewReader(`{"input":"no"}`))
	assertRuntimeError(t, stopping, http.StatusServiceUnavailable, "RUNTIME_STOPPING", "Runtime is stopping")
	if err := <-firstResult; !errors.Is(err, context.Canceled) {
		t.Fatalf("unexpected first stop result: %v", err)
	}

	secondCancel, secondResult := startRuntimeForTest(t, rt)
	response := invokeRequest(rt, strings.NewReader(`{"input":"yes"}`))
	if response.Code != http.StatusOK || strings.TrimSpace(response.Body.String()) != `"yes"` {
		t.Fatalf("restart invocation failed: %d %s", response.Code, response.Body.String())
	}
	secondCancel()
	if err := <-secondResult; !errors.Is(err, context.Canceled) {
		t.Fatalf("unexpected second stop result: %v", err)
	}
}

func TestRuntimeDoesNotRestartOverUndrainedCallbackOwner(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	rt := newLimitedTestRuntime(RuntimeSettings{Port: "0", ShutdownTimeout: 20 * time.Millisecond})
	rt.callbackClient.retryDelays = []int{0}
	rt.callbackClient.httpClient = &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		close(started)
		<-release
		return &http.Response{StatusCode: http.StatusNoContent, Body: http.NoBody}, nil
	})}
	reservation := rt.callbackDispatcher.TryReserve()
	if reservation == nil {
		t.Fatal("callback reservation failed")
	}
	if err := rt.callbackDispatcher.SubmitReserved(context.Background(), reservation,
		"exec-1", Success("ok"), "", "1"); err != nil {
		t.Fatal(err)
	}
	<-started
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer shutdownCancel()
	if err := rt.callbackDispatcher.Shutdown(shutdownCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected bounded undrained shutdown, got %v", err)
	}

	startCtx, startCancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer startCancel()
	if err := rt.Start(startCtx); !errors.Is(err, errRuntimeNotDrained) {
		t.Fatalf("restart replaced an undrained callback owner: %v", err)
	}
	close(release)
	waitForDispatcherDrain(t, rt.callbackDispatcher)
}

func startRuntimeForTest(t *testing.T, rt *Runtime) (context.CancelFunc, <-chan error) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() { result <- rt.Start(ctx) }()
	waitForRuntimeState(t, rt, runtimeStateRunning)
	return cancel, result
}

func waitForRuntimeState(t *testing.T, rt *Runtime, expected runtimeState) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := rt.waitForState(ctx, expected); err != nil {
		t.Fatalf("runtime did not reach %v: %v", expected, err)
	}
}

func TestRuntimeStartHonorsShutdownContext(t *testing.T) {
	blocker := make(chan struct{})
	rt := NewRuntime(WithSettings(RuntimeSettings{Port: "0", ShutdownTimeout: 50 * time.Millisecond}))
	rt.callbackDispatcher = &CallbackDispatcher{jobs: make(chan callbackJob)}
	rt.callbackDispatcher.wg.Add(1)
	go func() {
		defer rt.callbackDispatcher.wg.Done()
		<-blocker
	}()

	ctx, cancel := context.WithCancel(context.Background())
	resultCh := make(chan error, 1)
	go func() {
		resultCh <- rt.Start(ctx)
	}()

	cancel()

	select {
	case err := <-resultCh:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected context canceled, got %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("expected Start to return after context cancellation")
	}

	close(blocker)
}
