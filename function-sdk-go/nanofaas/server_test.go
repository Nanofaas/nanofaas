package nanofaas

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
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

func TestRuntimeStartHonorsShutdownContext(t *testing.T) {
	blocker := make(chan struct{})
	rt := NewRuntime(WithSettings(RuntimeSettings{Port: "0"}))
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
