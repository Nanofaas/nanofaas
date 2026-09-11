package nanofaas

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestCallbackDispatcherReturnsFalseWhenQueueIsFull(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	client := NewCallbackClient("http://callback/v1/internal/executions")
	client.retryDelays = []int{0}
	client.httpClient = &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		select {
		case <-started:
		default:
			close(started)
		}
		<-release
		return &http.Response{StatusCode: http.StatusNoContent, Body: http.NoBody}, nil
	})}
	dispatcher := NewCallbackDispatcher(client, 1, 1)
	defer dispatcher.Shutdown(context.Background())

	if !dispatcher.Submit(context.Background(), "active", Success("ok"), "") {
		t.Fatal("active callback was rejected")
	}
	<-started
	if !dispatcher.Submit(context.Background(), "queued", Success("ok"), "") {
		t.Fatal("queued callback was rejected")
	}
	ok := dispatcher.Submit(context.Background(), "saturated", Success("ok"), "")

	if ok {
		t.Fatal("expected queue rejection")
	}
	close(release)
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

func TestCallbackDispatcherReservesPendingCountAndBytesBeforeSerialization(t *testing.T) {
	dispatcher := newCallbackDispatcher(NewCallbackClient(""), 1, 1, 1, 64, 64)
	defer dispatcher.Shutdown(context.Background())

	reservation := dispatcher.TryReserve()
	if reservation == nil {
		t.Fatal("first callback reservation must succeed")
	}
	snapshot := dispatcher.snapshot()
	if snapshot.pendingCallbacks != 1 || snapshot.pendingCallbackBytes != 64 || snapshot.serializedCallbackBytes != 0 {
		t.Fatalf("unexpected reserved counters: %+v", snapshot)
	}
	if second := dispatcher.TryReserve(); second != nil {
		t.Fatal("count/byte saturated dispatcher accepted another callback")
	}

	reservation.Release()
	snapshot = dispatcher.snapshot()
	if snapshot.pendingCallbacks != 0 || snapshot.pendingCallbackBytes != 0 {
		t.Fatalf("callback reservation did not release: %+v", snapshot)
	}
}

func TestCallbackDispatcherRejectsSinglePayloadWithoutGrowingPastLimit(t *testing.T) {
	dispatcher := newCallbackDispatcher(NewCallbackClient(""), 1, 1, 1, 64, 64)
	defer dispatcher.Shutdown(context.Background())
	reservation := dispatcher.TryReserve()

	err := dispatcher.SubmitReserved(context.Background(), reservation, "exec-1",
		Success(strings.Repeat("x", 128)), "trace-1", "2")

	if !errors.Is(err, errPayloadTooLarge) {
		t.Fatalf("expected bounded serialization failure, got %v", err)
	}
	if snapshot := dispatcher.snapshot(); snapshot.serializedCallbackBytes != 0 || snapshot.pendingCallbackBytes != 64 {
		t.Fatalf("oversized callback retained unexpected bytes: %+v", snapshot)
	}
	reservation.Release()
}

func TestCallbackDispatcherReleasesAllReservationsAfterDelivery(t *testing.T) {
	requestStarted := make(chan struct{})
	releaseRequest := make(chan struct{})
	client := NewCallbackClient("http://callback/v1/internal/executions")
	client.retryDelays = []int{0}
	client.httpClient = &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		close(requestStarted)
		<-releaseRequest
		return &http.Response{StatusCode: http.StatusNoContent, Body: http.NoBody}, nil
	})}
	dispatcher := newCallbackDispatcher(client, 1, 1, 1, 256, 256)
	defer dispatcher.Shutdown(context.Background())
	reservation := dispatcher.TryReserve()
	if err := dispatcher.SubmitReserved(context.Background(), reservation, "exec-1", Success("ok"), "trace-1", "3"); err != nil {
		t.Fatalf("submit failed: %v", err)
	}
	<-requestStarted
	if snapshot := dispatcher.snapshot(); snapshot.pendingCallbacks != 1 || snapshot.pendingCallbackBytes != 256 || snapshot.serializedCallbackBytes == 0 {
		t.Fatalf("in-flight callback counters are not physical: %+v", snapshot)
	}
	close(releaseRequest)
	waitForDispatcherDrain(t, dispatcher)
}

func TestCallbackDispatcherShutdownWithFullQueueIsBoundedAndReleasesAll(t *testing.T) {
	requestStarted := make(chan struct{})
	client := NewCallbackClient("http://callback/v1/internal/executions")
	client.retryDelays = []int{0}
	client.httpClient = &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		select {
		case <-requestStarted:
		default:
			close(requestStarted)
		}
		<-req.Context().Done()
		return nil, req.Context().Err()
	})}
	dispatcher := newCallbackDispatcher(client, 1, 1, 2, 512, 256)
	submit := func(executionID string) {
		reservation := dispatcher.TryReserve()
		if reservation == nil {
			t.Fatalf("failed to reserve %s callback", executionID)
		}
		if err := dispatcher.SubmitReserved(context.Background(), reservation, executionID, Success("ok"), "", "1"); err != nil {
			t.Fatalf("failed to submit %s callback: %v", executionID, err)
		}
	}
	submit("active")
	<-requestStarted
	submit("queued")
	wantSerialized := int64(2 * len(`{"success":true,"output":"ok","error":null}`+"\n"))
	if got := dispatcher.snapshot().serializedCallbackBytes; got != wantSerialized {
		t.Fatalf("queued serialized bytes not fully owned: got %d want %d", got, wantSerialized)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	if err := dispatcher.Shutdown(ctx); err != nil {
		t.Fatalf("bounded shutdown failed: %v", err)
	}
	assertDispatcherDrained(t, dispatcher)
}

func waitForDispatcherDrain(t *testing.T, dispatcher *CallbackDispatcher) {
	t.Helper()
	deadline := time.After(time.Second)
	for {
		if snapshot := dispatcher.snapshot(); snapshot.pendingCallbacks == 0 &&
			snapshot.pendingCallbackBytes == 0 && snapshot.serializedCallbackBytes == 0 {
			return
		}
		select {
		case <-dispatcher.changedSignal():
		case <-deadline:
			t.Fatal("dispatcher did not drain")
		}
	}
}

func assertDispatcherDrained(t *testing.T, dispatcher *CallbackDispatcher) {
	t.Helper()
	snapshot := dispatcher.snapshot()
	if snapshot.pendingCallbacks != 0 || snapshot.pendingCallbackBytes != 0 || snapshot.serializedCallbackBytes != 0 {
		t.Fatalf("dispatcher retained resources after stop: %+v", snapshot)
	}
}
