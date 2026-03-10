package nanofaas

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestCallbackDispatcherReturnsFalseWhenQueueIsFull(t *testing.T) {
	client := NewCallbackClient("")
	dispatcher := NewCallbackDispatcher(client, 1, 1)
	defer dispatcher.Shutdown(context.Background())

	dispatcher.jobs <- callbackJob{executionID: "first", result: Success("ok")}
	ok := dispatcher.Submit(context.Background(), "second", Success("ok"), "")

	if ok {
		t.Fatal("expected queue rejection")
	}
}

func TestCallbackDispatcherShutdownReturnsQuickly(t *testing.T) {
	client := NewCallbackClient("")
	dispatcher := NewCallbackDispatcher(client, 1, 1)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	if err := dispatcher.Shutdown(ctx); err != nil {
		t.Fatalf("shutdown failed: %v", err)
	}
}

func TestCallbackDispatcherSubmitAfterShutdownDoesNotPanic(t *testing.T) {
	client := NewCallbackClient("")
	dispatcher := NewCallbackDispatcher(client, 1, 1)

	if err := dispatcher.Shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown failed: %v", err)
	}

	defer func() {
		if recovered := recover(); recovered != nil {
			t.Fatalf("submit panicked after shutdown: %v", recovered)
		}
	}()

	if ok := dispatcher.Submit(context.Background(), "late", Success("ok"), ""); ok {
		t.Fatal("expected submit to be rejected after shutdown")
	}
}

func TestCallbackDispatcherShutdownCancelsInFlightCallback(t *testing.T) {
	client := NewCallbackClient("http://callback/v1/internal/executions")
	client.httpClient = &http.Client{
		Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
			<-req.Context().Done()
			return nil, req.Context().Err()
		}),
		Timeout: 10 * time.Second,
	}
	client.retryDelays = []int{1000, 1000, 1000}

	dispatcher := NewCallbackDispatcher(client, 1, 4)
	if ok := dispatcher.Submit(context.Background(), "exec-1", Success("ok"), ""); !ok {
		t.Fatal("expected job to be enqueued")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()

	if err := dispatcher.Shutdown(ctx); err != nil {
		t.Fatalf("expected graceful shutdown, got %v", err)
	}
}

func TestCallbackClientHonorsCanceledContextDuringRetryDelay(t *testing.T) {
	client := NewCallbackClient("http://callback/v1/internal/executions")
	client.httpClient = &http.Client{
		Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
			return nil, errors.New("temporary network failure")
		}),
	}
	client.retryDelays = []int{1000, 1000, 1000}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	start := time.Now()
	if client.SendResult(ctx, "exec-1", Success("ok"), "") {
		t.Fatal("expected callback failure")
	}
	if time.Since(start) > 200*time.Millisecond {
		t.Fatalf("expected canceled context to stop retries quickly, took %s", time.Since(start))
	}
}
