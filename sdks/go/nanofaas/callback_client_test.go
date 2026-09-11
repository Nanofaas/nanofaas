package nanofaas

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestCallbackClientBuildsExecutionCompleteURL(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/internal/executions/exec-1:complete" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	client := NewCallbackClient(server.URL + "/v1/internal/executions")
	ok := client.SendResult(context.Background(), "exec-1", Success("ok"), "trace-1")
	if !ok {
		t.Fatalf("expected callback to succeed")
	}
}

func TestCallbackClientDoesNotRetryPermanent4xx(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		w.WriteHeader(http.StatusBadRequest)
	}))
	defer server.Close()

	client := NewCallbackClient(server.URL + "/v1/internal/executions")
	if client.SendResult(context.Background(), "exec-1", Success("ok"), "") {
		t.Fatal("expected callback failure")
	}
	if count.Load() != 1 {
		t.Fatalf("unexpected retry count %d", count.Load())
	}
}

func TestCallbackClientRetries429(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		current := count.Add(1)
		if current == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	client := NewCallbackClient(server.URL + "/v1/internal/executions")
	client.retryDelays = []int{0, 0, 0}
	if !client.SendResult(context.Background(), "exec-1", Success("ok"), "") {
		t.Fatal("expected callback success")
	}
	if count.Load() != 2 {
		t.Fatalf("unexpected retry count %d", count.Load())
	}
}

func TestCallbackClientNormalizesCompleteSuffix(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/exec-10:complete") {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	client := NewCallbackClient(server.URL + "/v1/internal/executions/placeholder:complete/")
	if !client.SendResult(context.Background(), "exec-10", Success("ok"), "") {
		t.Fatal("expected callback success")
	}
}

func TestCallbackClientRetriesPreserveDispatchAttemptIdentity(t *testing.T) {
	attempts := make(chan string, 3)
	client := NewCallbackClient("http://callback/v1/internal/executions")
	client.retryDelays = []int{0, 0, 0}
	client.httpClient = &http.Client{Transport: roundTripperFunc(func(req *http.Request) (*http.Response, error) {
		_, _ = io.Copy(io.Discard, req.Body)
		attempts <- req.Header.Get("X-Dispatch-Attempt")
		return &http.Response{StatusCode: http.StatusServiceUnavailable, Body: http.NoBody}, nil
	})}

	if client.SendResultWithDispatchAttempt(context.Background(), "exec-1", Success("ok"), "trace-1", "2") {
		t.Fatal("exhausted callback unexpectedly succeeded")
	}
	close(attempts)
	count := 0
	for attempt := range attempts {
		count++
		if attempt != "2" {
			t.Fatalf("callback retry changed dispatch attempt to %q", attempt)
		}
	}
	if count != 3 {
		t.Fatalf("unexpected attempt count %d", count)
	}
}
