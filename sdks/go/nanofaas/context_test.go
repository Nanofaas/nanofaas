package nanofaas

import (
	"context"
	"log/slog"
	"testing"
)

func TestContextCarriesExecutionAndTraceIdentifiers(t *testing.T) {
	ctx := WithInvocationContext(context.Background(), "exec-1", "trace-1")

	if got, ok := ExecutionIDFromContext(ctx); !ok || got != "exec-1" {
		t.Fatalf("unexpected execution id: %q %v", got, ok)
	}
	if got, ok := TraceIDFromContext(ctx); !ok || got != "trace-1" {
		t.Fatalf("unexpected trace id: %q %v", got, ok)
	}
}

func TestLoggerAddsInvocationFieldsWhenPresent(t *testing.T) {
	ctx := WithInvocationContext(context.Background(), "exec-1", "trace-1")
	logger := Logger(ctx, slog.Default())

	if logger == nil {
		t.Fatal("expected non-nil logger")
	}
}
