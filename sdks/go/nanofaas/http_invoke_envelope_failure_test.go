package nanofaas

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestEnvelopeRuntimeFailuresDoNotPublishFunctionHeaders(t *testing.T) {
	cases := []struct {
		name            string
		output          any
		settings        RuntimeSettings
		closeDispatcher bool
		status          int
		code            string
		message         string
	}{
		{"oversized-output", strings.Repeat("x", 512), RuntimeSettings{MaxOutputBytes: 128}, false,
			500, "RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit"},
		{"unserializable-output", func() {}, RuntimeSettings{}, false,
			500, "OUTPUT_SERIALIZATION_ERROR", "Handler output could not be serialized"},
		{"oversized-callback", strings.Repeat("x", 300), RuntimeSettings{MaxCallbackPayloadBytes: 256}, false,
			500, "RUNTIME_OUTPUT_TOO_LARGE", "Runtime output exceeds configured byte limit"},
		{"rejected-callback", "ok", RuntimeSettings{}, true,
			503, "RUNTIME_STOPPING", "Runtime is stopping"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			callback := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusNoContent)
			}))
			defer callback.Close()
			tc.settings.CallbackURL = callback.URL
			rt := NewRuntime(WithSettings(tc.settings))
			t.Cleanup(func() {
				ctx, cancel := context.WithTimeout(context.Background(), time.Second)
				defer cancel()
				if err := rt.callbackDispatcher.Shutdown(ctx); err != nil {
					t.Error(err)
				}
			})
			rt.Register("envelope", func(context.Context, InvocationRequest) (any, error) {
				if tc.closeDispatcher {
					ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
					defer cancel()
					_ = rt.callbackDispatcher.Shutdown(ctx)
				}
				return HandlerResponse{Output: tc.output, StatusCode: 201,
					Headers:  map[string]string{"Location": "/created", "ETag": "function", "Content-Type": "application/octet-stream"},
					Encoding: "base64"}, nil
			})
			response := invokeRequest(rt, strings.NewReader(`{"input":null}`))
			assertRuntimeError(t, response, tc.status, tc.code, tc.message)
			for _, key := range []string{"X-NanoFaaS-Function-Status", "X-NanoFaaS-Encoding", "Location", "ETag"} {
				if value := response.Header().Get(key); value != "" {
					t.Errorf("runtime failure leaked function header %s=%q", key, value)
				}
			}
			if response.Header().Get("Content-Type") != "application/json" {
				t.Errorf("runtime failure content type = %q", response.Header().Get("Content-Type"))
			}
			waitForDispatcherDrain(t, rt.callbackDispatcher)
			assertDispatcherDrained(t, rt.callbackDispatcher)
		})
	}
}
