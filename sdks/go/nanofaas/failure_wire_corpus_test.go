package nanofaas

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSharedFailureWireCorpus(t *testing.T) {
	var corpus struct {
		SuccessOutput map[string]any
		Config        struct {
			DeadlineMS, BodyReadTimeoutMS, CallbackAttemptTimeoutMS, CallbackMaxAttempts, DispatchAttempt int
			ExecutionID, TraceID                                                                          string
		}
		ContractDefinitions map[string]struct {
			HTTPStatus       int
			ErrorCode        *string
			HandlerStarted   bool
			CallbackAttempts int
			CallbackStatus   *int
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, "python3", "../../runtime-contract/validate_saturation_wire_corpus.py", "../../runtime-contract/failure-wire-corpus.json").CombinedOutput()
	if err != nil {
		t.Fatalf("shared failure validator: %v\n%s", err, output)
	}
	data, err := os.ReadFile("../../runtime-contract/failure-wire-corpus.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	for name, expected := range corpus.ContractDefinitions {
		t.Run(name, func(t *testing.T) {
			var mu sync.Mutex
			type callback struct {
				Payload              map[string]any
				Attempt, Trace, Path string
			}
			var calls []callback
			release := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var payload map[string]any
				if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
					t.Error(err)
					return
				}
				mu.Lock()
				calls = append(calls, callback{payload, r.Header.Get("X-Dispatch-Attempt"), r.Header.Get("X-Trace-Id"), r.URL.Path})
				mu.Unlock()
				if name == "callback-io-timeout" {
					select {
					case <-r.Context().Done():
					case <-release:
					}
					return
				}
				status := 204
				if expected.CallbackStatus != nil {
					status = *expected.CallbackStatus
				}
				w.WriteHeader(status)
			}))
			defer server.Close()
			defer close(release)
			rt := newLimitedTestRuntime(RuntimeSettings{CallbackURL: server.URL, CallbackMaxAttempts: corpus.Config.CallbackMaxAttempts, BodyReadTimeout: time.Duration(corpus.Config.BodyReadTimeoutMS) * time.Millisecond, CallbackAttemptTimeout: time.Duration(corpus.Config.CallbackAttemptTimeoutMS) * time.Millisecond})
			rt.callbackClient.retryDelays = make([]int, corpus.Config.CallbackMaxAttempts)
			var started atomic.Bool
			rt.Register("failure", func(context.Context, InvocationRequest) (any, error) {
				started.Store(true)
				if name == "envelope-serialization-failure" {
					return HandlerResponse{Output: func() {}, StatusCode: 201}, nil
				}
				return corpus.SuccessOutput, nil
			})
			var body io.Reader = strings.NewReader(`{"input":null}`)
			if name == "ingress-io-timeout" {
				body = &blockingBody{closed: make(chan struct{})}
			}
			req := httptest.NewRequest("POST", "/invoke", body)
			req.Header.Set("X-Execution-Id", corpus.Config.ExecutionID)
			req.Header.Set("X-Trace-Id", corpus.Config.TraceID)
			req.Header.Set("X-Dispatch-Attempt", strconv.Itoa(corpus.Config.DispatchAttempt))
			rec := httptest.NewRecorder()
			rt.Handler().ServeHTTP(rec, req)
			if rec.Code != expected.HTTPStatus || started.Load() != expected.HandlerStarted {
				t.Fatalf("status=%d started=%v body=%s", rec.Code, started.Load(), rec.Body.String())
			}
			var response map[string]any
			if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			if expected.ErrorCode != nil {
				e := response["error"].(map[string]any)
				if e["code"] != *expected.ErrorCode || e["message"] == "" {
					t.Fatalf("error=%v", e)
				}
			}
			ctx, cancel := context.WithTimeout(context.Background(), time.Duration(corpus.Config.DeadlineMS)*time.Millisecond)
			defer cancel()
			defer rt.callbackDispatcher.Shutdown(context.Background())
			for rt.callbackDispatcher.snapshot().pendingCallbacks != 0 {
				changed := rt.callbackDispatcher.changedSignal()
				if rt.callbackDispatcher.snapshot().pendingCallbacks == 0 {
					break
				}
				select {
				case <-changed:
				case <-ctx.Done():
					t.Fatal("callback resources did not drain")
				}
			}
			callbackSnap := rt.callbackDispatcher.snapshot()
			if callbackSnap.pendingCallbackBytes != 0 || callbackSnap.serializedCallbackBytes != 0 {
				t.Fatalf("retained callback resources=%+v", callbackSnap)
			}
			mu.Lock()
			defer mu.Unlock()
			if len(calls) != expected.CallbackAttempts {
				t.Fatalf("attempts=%d want=%d", len(calls), expected.CallbackAttempts)
			}
			for _, call := range calls {
				if !strings.Contains(call.Path, corpus.Config.ExecutionID) {
					t.Fatalf("callback path=%s", call.Path)
				}
				if call.Attempt != strconv.Itoa(corpus.Config.DispatchAttempt) || call.Trace != corpus.Config.TraceID {
					t.Fatalf("callback identity=%+v", call)
				}
				want := map[string]any{"success": true, "output": corpus.SuccessOutput, "error": nil}
				if expected.ErrorCode != nil {
					want = map[string]any{"success": false, "output": nil, "error": response["error"]}
				}
				if !reflect.DeepEqual(call.Payload, want) {
					t.Fatalf("callback payload=%v want=%v", call.Payload, want)
				}
			}
			snap := rt.limits.snapshot()
			if snap.activeHandlers != 0 || snap.inputBytes != 0 || snap.outputBytes != 0 {
				t.Fatalf("retained resources=%+v", snap)
			}
		})
	}
}
