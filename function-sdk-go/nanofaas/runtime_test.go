package nanofaas

import (
	"context"
	"testing"
)

func TestRuntimeResolvesSingleRegisteredHandler(t *testing.T) {
	rt := NewRuntime()
	rt.Register("echo", func(ctx context.Context, req InvocationRequest) (any, error) {
		return req.Input, nil
	})

	handler, err := rt.ResolveHandler()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}

	out, err := handler(context.Background(), InvocationRequest{Input: "hello"})
	if err != nil || out != "hello" {
		t.Fatalf("unexpected output: %v %v", out, err)
	}
}

func TestRuntimeRejectsMultipleHandlersWithoutSelection(t *testing.T) {
	rt := NewRuntime()
	rt.Register("alpha", func(ctx context.Context, req InvocationRequest) (any, error) { return nil, nil })
	rt.Register("beta", func(ctx context.Context, req InvocationRequest) (any, error) { return nil, nil })

	_, err := rt.ResolveHandler()
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestRuntimeUsesConfiguredHandlerName(t *testing.T) {
	rt := NewRuntime(WithSettings(RuntimeSettings{FunctionHandler: "beta"}))
	rt.Register("alpha", func(ctx context.Context, req InvocationRequest) (any, error) { return "alpha", nil })
	rt.Register("beta", func(ctx context.Context, req InvocationRequest) (any, error) { return "beta", nil })

	handler, err := rt.ResolveHandler()
	if err != nil {
		t.Fatalf("resolve failed: %v", err)
	}

	got, err := handler(context.Background(), InvocationRequest{})
	if err != nil || got != "beta" {
		t.Fatalf("unexpected handler result: %v %v", got, err)
	}
}
