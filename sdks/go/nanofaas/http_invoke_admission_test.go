package nanofaas

import (
	"context"
	"net/http"
	"strings"
	"testing"
)

func TestInvokeSaturationPrecedencePreservesReservations(t *testing.T) {
	for _, tc := range []struct {
		name                      string
		handlerFull, callbackFull bool
		code, message             string
	}{
		{"both-full", true, true, "RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted"},
		{"handler-full", true, false, "RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted"},
		{"callback-full", false, true, "RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			rt := newLimitedTestRuntime(RuntimeSettings{MaxConcurrentHandlers: 1,
				MaxPendingCallbacks: 1, MaxPendingCallbackBytes: 1024, MaxCallbackPayloadBytes: 1024})
			t.Cleanup(func() { _ = rt.callbackDispatcher.Shutdown(context.Background()) })
			rt.Register("never", func(context.Context, InvocationRequest) (any, error) {
				t.Error("rejected invocation started handler")
				return nil, nil
			})
			var callback *CallbackReservation
			if tc.callbackFull {
				callback = rt.callbackDispatcher.TryReserve()
				if callback == nil {
					t.Fatal("fixture callback reservation failed")
				}
				defer callback.Release()
			}
			var handler *handlerReservation
			if tc.handlerFull {
				handler = rt.limits.tryReserveHandler()
				if handler == nil {
					t.Fatal("fixture handler reservation failed")
				}
				handler.retainInput(128)
				defer handler.release()
			}
			beforeHandlers, beforeCallbacks := rt.limits.snapshot(), rt.callbackDispatcher.snapshot()
			response := invokeRequest(rt, strings.NewReader(`{"input":null}`))
			assertRuntimeError(t, response, http.StatusTooManyRequests, tc.code, tc.message)
			if response.Header().Get("Retry-After") != "1" {
				t.Fatal("missing retry hint")
			}
			if got := rt.limits.snapshot(); got != beforeHandlers {
				t.Fatalf("handler accounting changed: %+v -> %+v", beforeHandlers, got)
			}
			if got := rt.callbackDispatcher.snapshot(); got != beforeCallbacks {
				t.Fatalf("callback accounting changed: %+v -> %+v", beforeCallbacks, got)
			}
			handler.release()
			callback.Release()
			if got := rt.limits.snapshot(); got.activeHandlers != 0 || got.inputBytes != 0 || got.outputBytes != 0 {
				t.Fatalf("handler leak: %+v", got)
			}
			if got := rt.callbackDispatcher.snapshot(); got != (callbackDispatcherSnapshot{}) {
				t.Fatalf("callback leak: %+v", got)
			}
		})
	}
}
