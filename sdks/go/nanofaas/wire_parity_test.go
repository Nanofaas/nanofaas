package nanofaas

import (
	"context"
	"testing"
)

// These constants are the frozen wire contract, shared with platform/common (Java),
// sdks/python, and sdks/javascript. Changing one without the others silently breaks
// cross-language dispatch.
func TestWireContractConstants(t *testing.T) {
	rec := invokeWithHandler(t, func(ctx context.Context, req InvocationRequest) (any, error) {
		return HandlerResponse{Output: "x", StatusCode: 201, Encoding: "base64"}, nil
	})

	if got := rec.Header().Get("X-NanoFaaS-Function-Status"); got != "true" {
		t.Errorf(`marker header must be exactly "X-NanoFaaS-Function-Status: true", got %q`, got)
	}
	if got := rec.Header().Get("X-NanoFaaS-Encoding"); got != "base64" {
		t.Errorf(`encoding header must be exactly "X-NanoFaaS-Encoding", got %q`, got)
	}
}
